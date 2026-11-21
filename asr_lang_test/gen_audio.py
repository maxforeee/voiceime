#!/usr/bin/env python3
"""Generate test audio for the ASR language/mixed test (v1.3.0).

Same method as M1 (m1_verify/gen_audio.py): neural TTS (edge-tts) ->
16 kHz 16-bit mono WAV, leading/trailing silence trimmed.
- zh group: reuse M1's already-generated s1-s6.wav (copied).
- en group: en-US-AriaNeural.
- mix group: zh-CN-XiaoxiaoNeural reading Chinese with English words
  inserted (a Chinese speaker's voice, like the user's real usage).
"""
import array
import asyncio
import json
import math
import os
import shutil
import subprocess
import wave
from pathlib import Path

import edge_tts

HERE = Path(__file__).resolve().parent
M1_DIR = HERE.parent / "m1_verify"
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
    items = json.loads((HERE / "sentences.json").read_text(encoding="utf-8"))
    for item in items:
        wav = HERE / f"{item['id']}.wav"
        if wav.exists():
            print(f"{item['id']}.wav exists, skip")
            continue
        if item.get("m1_wav"):
            src = M1_DIR / item["m1_wav"]
            shutil.copyfile(src, wav)
            print(f"{item['id']}.wav copied from M1 {item['m1_wav']}")
            continue
        mp3 = HERE / f"{item['id']}.mp3"
        tts = edge_tts.Communicate(item["ref"], item["voice"], rate="+0%", proxy=PROXY)
        await tts.save(str(mp3))
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", str(mp3),
             "-ar", "16000", "-ac", "1", "-sample_fmt", "s16", str(wav)],
            check=True,
        )
        trim_silence(wav)
        with wave.open(str(wav), "rb") as w:
            dur = w.getnframes() / RATE
        print(f"{item['id']}.wav ready ({dur:.2f}s): {item['ref']}")


if __name__ == "__main__":
    asyncio.run(main())
