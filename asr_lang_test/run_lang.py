#!/usr/bin/env python3
"""ASR language / mixed-speech test (v1.3.0).

Same streaming harness as M1 (m1_verify/run_m1.py): 16 kHz mono PCM ->
Doubao sauc bigmodel_async, CER + English-word hit rate per sentence.

Variants (all on the SAME audio, so differences are the request's doing):
  base      current app request: no language, no enable_lid
  lid       request.enable_lid = true (official: enable Chinese-English
            and dialect recognition; default false)
  lang_zh   audio.language = "zh-CN" (locks to Chinese if honored)
  lang_en   audio.language = "en-US" (locks to English if honored)
  auto_lang request.enable_auto_lang = true (auto-detect one language)

Key is read ONLY from env DOUBAO_API_KEY_FROM_CONFIG and never logged.
"""
import argparse
import asyncio
import gzip
import json
import os
import re
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

VARIANTS = {
    "base": {},
    "lid": {"request": {"enable_lid": True}},
    "lang_zh": {"audio": {"language": "zh-CN"}},
    "lang_en": {"audio": {"language": "en-US"}},
    "auto_lang": {"request": {"enable_auto_lang": True}},
}


def hdr(msg_type, flags, serial, compress):
    return bytes([0x11, (msg_type << 4) | flags, (serial << 4) | compress, 0x00])


def full_client_request(payload, seq=1):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    return hdr(1, 1, 1, 0) + struct.pack(">i", seq) + struct.pack(">I", len(body)) + body


def audio_packet(pcm, seq, last=False):
    flags = 3 if last else 1
    s = -seq if last else seq
    return hdr(2, flags, 0, 0) + struct.pack(">i", s) + struct.pack(">I", len(pcm)) + pcm


def parse_response(data):
    msg_type = data[1] >> 4
    flags = data[1] & 0x0F
    serial = data[2] >> 4
    compress = data[2] & 0x0F
    off = 4
    seq = None
    code = None
    if msg_type == 0x0F:
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


def read_wav_pcm(path):
    with wave.open(str(path), "rb") as w:
        assert w.getframerate() == SAMPLE_RATE and w.getnchannels() == 1 and w.getsampwidth() == 2, \
            f"{path}: expected 16 kHz 16-bit mono"
        return w.readframes(w.getnframes())


PUNCT = "，。！？、；：,.!?;:…—·\"'“”‘’（）()《》<>【】[] \t\n\r"


def norm(text):
    return "".join(ch for ch in text if ch not in PUNCT)


def cer(ref, hyp):
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


def en_word_hits(en_words, hyp):
    """How many of the reference's English words appear in the hypothesis.
    ITN is off, but a number word may still come back as digits, so
    26 -> 'twenty six' and a standalone 9 -> 'nine' count as hits."""
    t = hyp.lower()
    t = re.sub(r"\b26\b", "twenty six", t)
    t = re.sub(r"\b9\b", "nine", t)
    hits = []
    for w in en_words:
        if re.search(r"\b" + re.escape(w.lower()) + r"\b", t):
            hits.append(w)
    return hits


async def run_sentence(wav_path, ref, api_key, variant):
    pcm = read_wav_pcm(wav_path)
    audio_dur = len(pcm) / (SAMPLE_RATE * 2)
    headers = {
        "X-Api-Key": api_key,
        "X-Api-Resource-Id": RESOURCE_ID,
        "X-Api-Connect-Id": str(uuid.uuid4()),
    }
    req = {
        "user": {"uid": "asr_lang_test"},
        "audio": {"format": "pcm", "codec": "raw", "rate": SAMPLE_RATE, "bits": 16, "channel": 1},
        "request": {
            "model_name": "bigmodel",
            "enable_punc": True,
            "enable_itn": False,
            "show_utterances": True,
            "result_type": "full",
        },
    }
    for section, fields in VARIANTS[variant].items():
        req[section].update(fields)
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

    rate, dist, nref, errs = cer(ref, final_text)
    return {
        "variant": variant,
        "wav": wav_path.name,
        "reference": ref,
        "hypothesis": final_text,
        "audio_duration_s": round(audio_dur, 3),
        "cer": round(rate, 4),
        "edit_distance": dist,
        "ref_chars": nref,
        "errors": errs,
        "tail_latency_s": round((final_evt["t"] - t_audio_end), 3) if final_evt and t_audio_end else None,
        "error": error,
    }


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--variants", default="base,lid")
    ap.add_argument("--groups", default="zh,en,mix")
    ap.add_argument("--only", default="")
    args = ap.parse_args()
    variants = [v for v in args.variants.split(",") if v in VARIANTS]
    if not variants:
        print("no valid variants; choose from:", ", ".join(VARIANTS), file=sys.stderr)
        sys.exit(2)
    api_key = os.environ.get("DOUBAO_API_KEY_FROM_CONFIG", "").strip()
    if not api_key:
        print("key not provided")
        sys.exit(2)
    items = json.loads((HERE / "sentences.json").read_text(encoding="utf-8"))
    if args.only:
        keep = set(args.only.split(","))
        items = [it for it in items if it["id"] in keep]
    else:
        groups = set(args.groups.split(","))
        items = [it for it in items if it["group"] in groups]
    results = []
    for variant in variants:
        for it in items:
            wav = HERE / f"{it['id']}.wav"
            if not wav.exists():
                print(f"missing {wav}; run gen_audio.py first", file=sys.stderr)
                sys.exit(2)
            print(f"[{variant}] {it['id']} {it['ref']}", flush=True)
            try:
                r = await run_sentence(wav, it["ref"], api_key, variant)
            except Exception as exc:
                r = {"variant": variant, "wav": wav.name, "reference": it["ref"],
                     "error": {"code": -1, "message": repr(exc)}}
            r["id"] = it["id"]
            r["group"] = it["group"]
            if it.get("en_words"):
                r["en_words"] = it["en_words"]
                r["en_hits"] = en_word_hits(it["en_words"], r.get("hypothesis", ""))
            results.append(r)
            if r.get("error"):
                print(f"  ERROR: {r['error']}", flush=True)
            else:
                extra = ""
                if it.get("en_words"):
                    extra = f" en_words {len(r['en_hits'])}/{len(it['en_words'])} {r['en_hits']}"
                print(f"  hyp: {r['hypothesis']}", flush=True)
                print(f"  CER={r['cer']:.2%}{extra}", flush=True)
    tag = "_".join(variants)
    out = HERE / f"results_{tag}.json"
    out.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nresults -> {out}")
    for variant in variants:
        for group in ("zh", "en", "mix"):
            rows = [r for r in results if r["variant"] == variant and r["group"] == group
                    and not r.get("error")]
            if not rows:
                continue
            td = sum(r["edit_distance"] for r in rows)
            tr = sum(r["ref_chars"] for r in rows)
            line = f"SUMMARY {variant} {group}: CER={td / tr:.2%} ({td}/{tr}) on {len(rows)} sentences"
            if group == "mix":
                th = sum(len(r.get("en_hits", [])) for r in rows)
                tt = sum(len(r.get("en_words", [])) for r in rows)
                line += f" | English words {th}/{tt}"
            print(line, flush=True)


if __name__ == "__main__":
    asyncio.run(main())
