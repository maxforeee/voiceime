#!/usr/bin/env python3
"""M1 harness: stream 16 kHz mono PCM to Doubao sauc bigmodel_async and
measure recognition accuracy (CER) and streaming latency.

Key is read ONLY from env DOUBAO_API_KEY_FROM_CONFIG and never logged.
"""
import asyncio
import gzip
import json
import os
import struct
import sys
import time
import uuid
import wave
from pathlib import Path

import websockets

ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
RESOURCE_ID = "volc.seedasr.sauc.duration"
HERE = Path(__file__).resolve().parent
CHUNK_MS = 100
SAMPLE_RATE = 16000
CHUNK_BYTES = SAMPLE_RATE * 2 * CHUNK_MS // 1000


def hdr(msg_type: int, flags: int, serial: int, compress: int) -> bytes:
    return bytes([0x11, (msg_type << 4) | flags, (serial << 4) | compress, 0x00])


def full_client_request(payload: dict, seq: int = 1) -> bytes:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    return hdr(1, 1, 1, 0) + struct.pack(">i", seq) + struct.pack(">I", len(body)) + body


def audio_packet(pcm: bytes, seq: int, last: bool = False) -> bytes:
    flags = 3 if last else 1
    s = -seq if last else seq
    return hdr(2, flags, 0, 0) + struct.pack(">i", s) + struct.pack(">I", len(pcm)) + pcm


def parse_response(data: bytes) -> dict:
    msg_type = data[1] >> 4
    flags = data[1] & 0x0F
    serial = data[2] >> 4
    compress = data[2] & 0x0F
    off = 4
    seq = None
    code = None
    if msg_type == 0x0F:  # error response: code, size, payload (no sequence)
        code = struct.unpack(">I", data[off:off + 4])[0]
        off += 4
    elif flags & 0x01:
        seq = struct.unpack(">i", data[off:off + 4])[0]
        off += 4
    size = struct.unpack(">I", data[off:off + 4])[0]
    off += 4
    payload = data[off:off + size]
    if compress == 1:
        payload = gzip.decompress(payload)
    out = {"seq": seq, "flags": flags, "is_last": bool(flags & 0x02)}
    if msg_type == 0x0F:
        out["error"] = {"code": code, "message": payload.decode("utf-8", "replace")}
        return out
    if serial == 1:
        out["payload"] = json.loads(payload.decode("utf-8"))
    else:
        out["payload"] = {"raw": payload.decode("utf-8", "replace")}
    return out


def read_wav_pcm(path: Path) -> bytes:
    with wave.open(str(path), "rb") as w:
        assert w.getframerate() == SAMPLE_RATE and w.getnchannels() == 1 and w.getsampwidth() == 2, \
            f"{path}: expected 16 kHz 16-bit mono"
        return w.readframes(w.getnframes())


PUNCT = "，。！？、；：,.!?;:…—·\"'“”‘’（）()《》<>【】[] \t\n\r"


def norm(text: str) -> str:
    return "".join(ch for ch in text if ch not in PUNCT)


def cer(ref: str, hyp: str):
    """Char error rate + aligned error list (sub/del/ins)."""
    r, h = norm(ref), norm(hyp)
    n, m = len(r), len(h)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1,
                          d[i - 1][j - 1] + (r[i - 1] != h[j - 1]))
    i, j, errors = n, m, []
    while i > 0 or j > 0:
        if i > 0 and j > 0 and d[i][j] == d[i - 1][j - 1] + (r[i - 1] != h[j - 1]):
            if r[i - 1] != h[j - 1]:
                errors.append({"type": "sub", "ref": r[i - 1], "hyp": h[j - 1]})
            i, j = i - 1, j - 1
        elif i > 0 and d[i][j] == d[i - 1][j] + 1:
            errors.append({"type": "del", "ref": r[i - 1]})
            i -= 1
        else:
            errors.append({"type": "ins", "hyp": h[j - 1]})
            j -= 1
    errors.reverse()
    return (d[n][m] / n if n else 0.0), d[n][m], n, errors


async def run_sentence(wav_path: Path, ref: str, api_key: str) -> dict:
    pcm = read_wav_pcm(wav_path)
    audio_dur = len(pcm) / (SAMPLE_RATE * 2)
    headers = {
        "X-Api-Key": api_key,
        "X-Api-Resource-Id": RESOURCE_ID,
        "X-Api-Connect-Id": str(uuid.uuid4()),
    }
    req = {
        "user": {"uid": "m1_verify"},
        "audio": {"format": "pcm", "codec": "raw", "rate": SAMPLE_RATE, "bits": 16, "channel": 1},
        "request": {
            "model_name": "bigmodel",
            "enable_punc": True,
            "enable_itn": False,
            "show_utterances": True,
            "result_type": "full",
        },
    }
    events = []
    t0 = time.monotonic()
    t_stream0 = None
    t_audio_end = None
    final_evt = None
    error = None

    connect_kwargs = dict(max_size=None, open_timeout=20, close_timeout=5)
    proxy = os.environ.get("https_proxy") or os.environ.get("HTTPS_PROXY")
    if proxy:
        connect_kwargs["proxy"] = proxy
    hdr_kw = "additional_headers" if int(websockets.__version__.split(".")[0]) >= 12 else "extra_headers"
    ws_ctx = websockets.connect(ENDPOINT, **{hdr_kw: headers}, **connect_kwargs)

    async with ws_ctx as ws:
        await ws.send(full_client_request(req))

        async def receiver():
            nonlocal final_evt, error
            async for msg in ws:
                t = time.monotonic() - t0
                r = parse_response(msg)
                r["t"] = t
                events.append(r)
                if "error" in r:
                    error = r["error"]
                    return
                if r.get("is_last"):
                    final_evt = r

        recv_task = asyncio.create_task(receiver())
        seq = 2
        total = len(pcm)
        sent = 0
        while sent < total:
            chunk = pcm[sent:sent + CHUNK_BYTES]
            sent += len(chunk)
            last = sent >= total
            if t_stream0 is None:
                t_stream0 = time.monotonic() - t0
            await ws.send(audio_packet(chunk, seq, last))
            seq += 1
            if last:
                t_audio_end = time.monotonic() - t0
            else:
                await asyncio.sleep(CHUNK_MS / 1000)
        try:
            await asyncio.wait_for(recv_task, timeout=30)
        except asyncio.TimeoutError:
            recv_task.cancel()
            error = error or {"code": -1, "message": "timeout waiting for final response"}

    if final_evt is None and events:
        final_evt = events[-1]
    final_text = ""
    if final_evt and "payload" in final_evt:
        final_text = (final_evt["payload"].get("result") or {}).get("text", "")

    # Interim lag: receive time minus the wall-clock moment the covered audio was spoken.
    interim = []
    for e in events:
        p = e.get("payload") or {}
        utts = (p.get("result") or {}).get("utterances") or []
        if not utts or e is final_evt:
            continue
        covered_ms = max(u.get("end_time", 0) for u in utts)
        text = (p.get("result") or {}).get("text", "")
        if not text:
            continue
        spoken_at = (t_stream0 or 0) + covered_ms / 1000.0
        interim.append({
            "t": round(e["t"], 3),
            "covered_ms": covered_ms,
            "lag_s": round(e["t"] - spoken_at, 3),
            "text": text,
            "definite": [bool(u.get("definite")) for u in utts],
        })

    rate, dist, nref, errs = cer(ref, final_text)
    lags = [x["lag_s"] for x in interim]
    return {
        "wav": wav_path.name,
        "reference": ref,
        "hypothesis": final_text,
        "audio_duration_s": round(audio_dur, 3),
        "cer": round(rate, 4),
        "edit_distance": dist,
        "ref_chars": nref,
        "errors": errs,
        "tail_latency_s": round((final_evt["t"] - t_audio_end), 3) if final_evt and t_audio_end else None,
        "final_at_s": round(final_evt["t"], 3) if final_evt else None,
        "interim_count": len(interim),
        "interim_lag_max_s": max(lags) if lags else None,
        "interim_lag_median_s": sorted(lags)[len(lags) // 2] if lags else None,
        "interim": interim,
        "error": error,
        "raw_events": events,
    }


async def main():
    api_key = os.environ.get("DOUBAO_API_KEY_FROM_CONFIG", "").strip()
    if not api_key:
        print("key not provided")
        sys.exit(2)
    refs = json.loads((HERE / "sentences.json").read_text(encoding="utf-8"))
    results = []
    for idx, ref in enumerate(refs, 1):
        wav = HERE / f"s{idx}.wav"
        if not wav.exists():
            print(f"missing {wav}; run gen_audio.py first", file=sys.stderr)
            sys.exit(2)
        print(f"[{idx}/{len(refs)}] {ref}", flush=True)
        try:
            r = await run_sentence(wav, ref, api_key)
        except Exception as exc:  # noqa: BLE001 - report and continue
            r = {"wav": wav.name, "reference": ref, "error": {"code": -1, "message": repr(exc)}}
        results.append(r)
        if r.get("error"):
            print(f"  ERROR: {r['error']}", flush=True)
        else:
            print(f"  hyp: {r['hypothesis']}", flush=True)
            print(f"  CER={r['cer']:.2%} tail={r['tail_latency_s']}s "
                  f"interim_n={r['interim_count']} lag_max={r['interim_lag_max_s']}s", flush=True)
    out = HERE / "results.json"
    out.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    ok = [r for r in results if not r.get("error") and r.get("hypothesis") is not None]
    if ok:
        total_dist = sum(r["edit_distance"] for r in ok)
        total_ref = sum(r["ref_chars"] for r in ok)
        print(f"\nOVERALL CER={total_dist / total_ref:.2%} ({total_dist}/{total_ref}) "
              f"on {len(ok)}/{len(results)} sentences; results -> {out}")


if __name__ == "__main__":
    asyncio.run(main())
