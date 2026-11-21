#!/usr/bin/env python3
"""VoiceIME build-time lexicon converter (NOT app code).

Turns m2_verify/luna_pinyin.dict.yaml + m2_verify/ice_base.dict.yaml into a
compact SQLite DB bundled in the APK (app/src/main/assets/lexicon.db), with
rankings baked from m2_verify/zh_full.txt (build-time only, never shipped).

Parsing / normalization / ranking replicate m2_verify/t9_verify.py exactly:
  - T9 map 2=abc 3=def 4=ghi 5=jkl 6=mno 7=pqrs 8=tuv 9=wxyz
  - pinyin ü/ǖ -> v, weights: luna "NN%" -> /100 clamped, ice int -> /file max
  - score = (log10(corpus count) if count else -1.0, normalized dict weight)

Trim policy (to hit the few-MB target): keep an entry when
  - it is a single-syllable single character (full char coverage), OR
  - its corpus frequency count >= --min-freq (default 1), OR
  - its normalized dict weight >= --min-weight (default 0 = off).
Rare phrases with no corpus frequency are dropped; the app falls back to
per-syllable characters for those (same fallback idea as t9_verify.py).

Schema (schema_version=1):
  meta(k TEXT PK, v TEXT)
  word(id INTEGER PK, w TEXT, f INTEGER)          -- f = raw corpus count (0 if absent)
  pykey(p TEXT PK, ws BLOB) WITHOUT ROWID         -- p = joined pinyin, ws = packed
                                                  --   rank-ordered (varint wid, u16 weight*65535)
  prefix(d TEXT, wid INTEGER, s INTEGER) + idx    -- digit-prefix index over the top
                                                  --   --prefix-top frequent words (best reading)
  syl(d TEXT, s TEXT)                             -- 424-syllable T9 table

Usage: python3 tools/build_lexicon.py [--min-freq N] [--min-weight F] [--prefix-top N]
"""
import argparse
import gzip
import math
import os
import sqlite3
import struct
import sys
import time
from collections import defaultdict
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "m2_verify")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "lexicon.db")

FILES = [
    ("luna_pinyin.dict.yaml", "pct"),
    ("ice_base.dict.yaml", "int"),
]
FREQ_FILE = "zh_full.txt"

T9 = {}
for d, letters in {"2": "abc", "3": "def", "4": "ghi", "5": "jkl",
                   "6": "mno", "7": "pqrs", "8": "tuv", "9": "wxyz"}.items():
    for ch in letters:
        T9[ch] = d


def digit_str(s):
    return "".join(T9.get(c, "?") for c in s)


def parse_weight(raw, kind):
    if raw is None:
        return 0.0
    raw = raw.strip()
    try:
        if kind == "pct":
            return max(0.0, min(1.0, float(raw.rstrip("%")) / 100.0))
        return float(raw)
    except ValueError:
        return 0.0


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def pack_score(fs, norm):
    """Single int64 sort key, monotonic in (freq_log, norm_weight)."""
    return int(round((fs + 1.0) * 100000)) * 65536 + int(round(norm * 65535))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-freq", type=int, default=1)
    ap.add_argument("--min-weight", type=float, default=0.0)
    ap.add_argument("--prefix-top", type=int, default=60000)
    args = ap.parse_args()

    t0 = time.time()
    word_by_pinyin = defaultdict(list)  # key -> [(norm, order, word)]
    syl_set = set()
    order = 0
    for fname, kind in FILES:
        path = os.path.join(SRC, fname)
        raw_weights, rows = [], []
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                if line.startswith("#") or "\t" not in line:
                    continue
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 2:
                    continue
                word = parts[0]
                pinyin = parts[1].strip().replace("\u00fc", "v").replace("\u01d6", "v")
                w = parse_weight(parts[2] if len(parts) > 2 else None, kind)
                syls = pinyin.split()
                if not syls or not word:
                    continue
                rows.append((word, syls, w))
                raw_weights.append(w)
                syl_set.update(syls)
        mx = max(raw_weights) if raw_weights else 1.0
        for word, syls, w in rows:
            norm = w if kind == "pct" else (w / mx if mx else 0.0)
            word_by_pinyin["".join(syls)].append((norm, order, word))
            order += 1
        print(f"{fname}: entries={len(rows)}", flush=True)

    freq = {}
    with open(os.path.join(SRC, FREQ_FILE), encoding="utf-8", errors="replace") as f:
        for line in f:
            p = line.split()
            if len(p) >= 2:
                try:
                    freq[p[0]] = int(p[1])
                except ValueError:
                    pass
    print(f"freq list: {len(freq)} words; merged keys={len(word_by_pinyin)} "
          f"syllables={len(syl_set)} parse={time.time()-t0:.1f}s", flush=True)

    def fs_of(word):
        c = freq.get(word)
        return math.log10(c) if c else -1.0

    # ---- trim + rank --------------------------------------------------------
    kept_entries = 0
    dropped_entries = 0
    word_ids = {}          # word -> id
    word_freq = {}         # word -> corpus count
    py_rows = []           # (pinyin_key, blob)
    prefix_rows = []       # (digits, wid, score)
    best_per_word = {}     # word -> (score, wid) for the prefix index

    for key, lst in word_by_pinyin.items():
        ranked = sorted(lst, key=lambda t: (-fs_of(t[2]), -t[0], t[1]))
        kept = []
        for norm, _o, word in ranked:
            is_char = len(word) == 1 and key in syl_set
            weight_ok = args.min_weight > 0.0 and norm >= args.min_weight
            if is_char or freq.get(word, 0) >= args.min_freq or weight_ok:
                kept.append((norm, word))
            else:
                dropped_entries += 1
        if not kept:
            continue
        blob = bytearray()
        for norm, word in kept:
            if word not in word_ids:
                word_ids[word] = len(word_ids) + 1
                word_freq[word] = freq.get(word, 0)
            wid = word_ids[word]
            blob += varint(wid)
            blob += struct.pack(">H", int(round(norm * 65535)))
            kept_entries += 1
            s = pack_score(fs_of(word), norm)
            if word not in best_per_word or s > best_per_word[word][0]:
                best_per_word[word] = (s, wid, key)
        py_rows.append((key, bytes(blob)))

    # prefix index: top-N frequent words, one row per word (best reading)
    top_words = sorted(best_per_word.items(), key=lambda kv: -kv[1][0])[: args.prefix_top]
    for word, (s, wid, key) in top_words:
        d = digit_str(key)
        if "?" not in d:
            prefix_rows.append((d, wid, s))

    syl_rows = []
    for s in sorted(syl_set):
        d = digit_str(s)
        if "?" not in d:
            syl_rows.append((d, s))

    # ---- write DB -----------------------------------------------------------
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    tmp = OUT + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    con = sqlite3.connect(tmp)
    con.executescript("""
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
        CREATE TABLE word(id INTEGER PRIMARY KEY, w TEXT NOT NULL, f INTEGER NOT NULL);
        CREATE TABLE pykey(p TEXT PRIMARY KEY, ws BLOB NOT NULL) WITHOUT ROWID;
        CREATE TABLE prefix(d TEXT NOT NULL, wid INTEGER NOT NULL, s INTEGER NOT NULL);
        CREATE INDEX idx_prefix_d ON prefix(d);
        CREATE TABLE syl(d TEXT NOT NULL, s TEXT NOT NULL);
    """)
    con.executemany("INSERT INTO word VALUES (?,?,?)",
                    ((wid, w, word_freq[w]) for w, wid in word_ids.items()))
    con.executemany("INSERT INTO pykey VALUES (?,?)", py_rows)
    con.executemany("INSERT INTO prefix VALUES (?,?,?)", prefix_rows)
    con.executemany("INSERT INTO syl VALUES (?,?)", syl_rows)
    meta = {
        "schema_version": "1",
        "built_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "sources": "rime-luna-pinyin (LGPLv3) + rime-ice base (GPLv3); ranks: FrequencyWords zh_full (OpenSubtitles, build-time only)",
        "policy": f"keep single chars OR freq>={args.min_freq} OR norm_weight>={args.min_weight}; prefix_top={args.prefix_top}",
        "words": str(len(word_ids)),
        "pykeys": str(len(py_rows)),
        "entries": str(kept_entries),
        "dropped_entries": str(dropped_entries),
        "syllables": str(len(syl_rows)),
    }
    con.executemany("INSERT INTO meta VALUES (?,?)", meta.items())
    con.commit()
    con.execute("VACUUM")
    con.close()
    os.replace(tmp, OUT)
    size = os.path.getsize(OUT)
    with open(OUT, "rb") as f:
        gz = len(gzip.compress(f.read(), 9))
    print(f"DB written: {OUT}\n  bytes={size} ({size/1e6:.2f} MB) gzip={gz} "
          f"words={len(word_ids)} pykeys={len(py_rows)} entries={kept_entries} "
          f"dropped={dropped_entries} prefix_rows={len(prefix_rows)} syls={len(syl_rows)} "
          f"total={time.time()-t0:.1f}s", flush=True)
    if size > 10_000_000:
        print("WARNING: DB larger than 10MB target; consider --min-freq 2", flush=True)


if __name__ == "__main__":
    sys.exit(main())
