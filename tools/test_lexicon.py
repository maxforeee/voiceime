#!/usr/bin/env python3
"""Box-side regression check for the BUILT lexicon.db (no JVM / no SDK needed).

Mirrors the Kotlin engine (Lexicon.kt) exactly: DP segmentation over the syl
table (cap 200), lookup of ALL segmentations in pykey, global ranking by
(freq_log, dict weight), dedupe by word, top 50 scrollable list, then the
digit-prefix index appended for partial input. Runs the 8 mandatory M2c
regression cases plus the extra t9_verify cases.

Usage: python3 tools/test_lexicon.py  -> prints table, writes tools/lexicon_test_results.json
"""
import json
import math
import os
import sqlite3
import sys
import time
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DB = os.path.join(ROOT, "app", "src", "main", "assets", "lexicon.db")

CASES = [
    ("64426", "你好"),
    ("94664486", "中国"),
    ("943943", "谢谢"),
    ("9245426", "再见"),
    ("743663", "什么"),
    ("542694", "练习"),
    ("96636", "我们"),
    ("96", "我"),
    # extras from the verified M2 lexicon run
    ("926", "玩"),
    ("926", "晚"),
    ("7484", "水"),
    ("5426", "练"),
    ("426", "汉"),
    ("84", "体"),
    ("2", "啊"),
    ("94664936", "中文"),
]


def pack_score(fs, norm):
    return int(round((fs + 1.0) * 100000)) * 65536 + int(round(norm * 65535))


class Engine:
    def __init__(self, path):
        self.con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        self.syl_by_digits = defaultdict(list)
        for d, s in self.con.execute("SELECT d, s FROM syl"):
            self.syl_by_digits[d].append(s)
        for d in self.syl_by_digits:
            self.syl_by_digits[d].sort()

    def segment(self, digits, cap=200):
        memo = {}

        def rec(i):
            if i == len(digits):
                return [[]]
            if i in memo:
                return memo[i]
            out = []
            for L in range(1, 7):
                if i + L > len(digits):
                    break
                chunk = digits[i:i + L]
                if chunk in self.syl_by_digits:
                    for s in self.syl_by_digits[chunk]:
                        for rest in rec(i + L):
                            out.append([s] + rest)
                            if len(out) >= cap:
                                memo[i] = out
                                return out
            memo[i] = out
            return out

        return rec(0)

    def lookup(self, digits, topn=50):
        t0 = time.time()
        segs = self.segment(digits)
        keys = list(dict.fromkeys("".join(s) for s in segs))
        entries = []  # (wid, weight, key)
        for k in keys:
            row = self.con.execute("SELECT ws FROM pykey WHERE p=?", (k,)).fetchone()
            if not row:
                continue
            blob = row[0]
            pos = 0
            while pos < len(blob):
                wid = 0
                shift = 0
                while True:
                    b = blob[pos]
                    pos += 1
                    wid |= (b & 0x7F) << shift
                    if not b & 0x80:
                        break
                    shift += 7
                weight = int.from_bytes(blob[pos:pos + 2], "big") / 65535.0
                pos += 2
                entries.append((wid, weight, k))
        words = {}
        if entries:
            ids = list(dict.fromkeys(w for w, _, _ in entries))
            q = ",".join("?" * len(ids))
            for wid, w, f in self.con.execute(
                    f"SELECT id, w, f FROM word WHERE id IN ({q})", ids):
                words[wid] = (w, f)
        best = {}
        for wid, weight, k in entries:
            w, f = words[wid]
            fs = math.log10(f) if f else -1.0
            s = pack_score(fs, weight)
            if w not in best or s > best[w][0]:
                best[w] = (s, k)
        ranked = sorted(best.items(), key=lambda kv: -kv[1][0])[:topn]
        cands = [(w, s) for w, (s, _) in ranked]
        # prefix index append (partial-input suggestions), deduped
        seen = {w for w, _ in cands}
        for wid, s in self.con.execute(
                "SELECT wid, s FROM prefix WHERE d GLOB ? ORDER BY s DESC LIMIT 30",
                (digits + "*",)):
            row = self.con.execute("SELECT w FROM word WHERE id=?", (wid,)).fetchone()
            if row and row[0] not in seen:
                cands.append((row[0], s))
                seen.add(row[0])
        ms = (time.time() - t0) * 1000
        return segs, cands, ms


def main():
    eng = Engine(DB)
    results = []
    ok_all = True
    print(f"DB: {DB} ({os.path.getsize(DB)/1e6:.2f} MB)")
    for digits, expect in CASES:
        segs, cands, ms = eng.lookup(digits)
        words = [w for w, _ in cands]
        rank = words.index(expect) + 1 if expect in words else None
        ok = rank is not None
        ok_all &= ok
        results.append({"digits": digits, "expected": expect, "rank": rank,
                        "pass": ok, "n_segmentations": len(segs),
                        "top10": words[:10], "ms": round(ms, 2)})
        print(f"[{digits:>8}] expect={expect} rank={rank} "
              f"{'PASS' if ok else 'FAIL'} segs={len(segs)} {ms:.1f}ms top10={words[:10]}")
    # 64646 must NOT be used as nihao; record what it yields (no common word expected)
    segs, cands, ms = eng.lookup("64646")
    print(f"[   64646] (not nihao) segs={len(segs)} top10={[w for w,_ in cands[:10]]}")
    results.append({"digits": "64646", "note": "not nihao; informational only",
                    "top10": [w for w, _ in cands[:10]], "n_segmentations": len(segs)})
    out = {"db_bytes": os.path.getsize(DB), "all_mandatory_pass": ok_all, "cases": results}
    path = os.path.join(HERE, "lexicon_test_results.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"\nmandatory+extra cases: {'ALL PASS' if ok_all else 'FAILURES PRESENT'} -> {path}")
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
