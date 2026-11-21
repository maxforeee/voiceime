#!/usr/bin/env python3
"""Generate Mandarin test audio: neural TTS (edge-tts) -> 16 kHz 16-bit mono WAV.

Trims leading/trailing silence by frame energy (keeps 0.1 s lead, 0.25 s tail)
so end-of-speech timing is meaningful without cutting internal pauses.
"""
import array
import asyncio
import json
import math
import os
import subprocess
import wave
from pathlib import Path

import edge_tts

HERE = Path(__file__).resolve().parent
VOICE = "zh-CN-XiaoxiaoNeural"
PROXY = os.environ.get("https_proxy") or os.environ.get("HTTPS_PROXY")
RATE = 16000


def trim_silence(path: Path, thresh: float = 300.0):
    with wave.open(str(path), "rb") as w:
        pcm = w.readframes(w.getnframes())
    samples = array.array("h")
    samples.frombytes(pcm)
    frame = 160  # 10 ms
    voiced = []
    for i in range(0, len(samples) - frame, frame):
        seg = samples[i:i + frame]
        rms = math.sqrt(sum(x * x for x in seg) / frame)
        if rms > thresh:
            voiced.append(i)
    if not voiced:
        return
    start = max(0, voiced[0] - RATE // 10)
    end = min(len(samples), voiced[-1] + frame + RATE // 4)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(samples[start:end].tobytes())


async def main():
    refs = json.loads((HERE / "sentences.json").read_text(encoding="utf-8"))
    for idx, text in enumerate(refs, 1):
        mp3 = HERE / f"s{idx}.mp3"
        wav = HERE / f"s{idx}.wav"
        if not mp3.exists():
            tts = edge_tts.Communicate(text, VOICE, rate="+0%", proxy=PROXY)
            await tts.save(str(mp3))
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", str(mp3),
             "-ar", "16000", "-ac", "1", "-sample_fmt", "s16", str(wav)],
            check=True,
        )
        trim_silence(wav)
        with wave.open(str(wav), "rb") as w:
            dur = w.getnframes() / RATE
        print(f"s{idx}.wav ready ({dur:.2f}s): {text}")


if __name__ == "__main__":
    asyncio.run(main())
