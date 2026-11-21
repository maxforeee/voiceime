#!/usr/bin/env python3
"""VoiceIME M2 verification v3 (Box): T9 pinyin segmentation + online lexicon + local fallback.
No keys used anywhere. Network only: GitHub raw (rime luna-pinyin + rime-ice base dict
+ FrequencyWords zh list) + Google Input Tools (no key).
Ranking (local): FrequencyWords count (primary) -> dict weight (tiebreak) -> file order.
Phases: A) download dicts  B) LOCAL-ONLY tests (no network calls)  C) online tests.
Writes m2_verify/local_results.json and m2_verify/online_results.json next to this script.
"""
import gzip, json, math, os, time, urllib.request
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
FILES = [
    ("luna_pinyin.dict.yaml",
     "https://raw.githubusercontent.com/rime/rime-luna-pinyin/master/luna_pinyin.dict.yaml",
     "pct"),   # weight like "100%"; single chars mainly
    ("ice_base.dict.yaml",
     "https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/base.dict.yaml",
     "int"),   # weight like 332885; phrases mainly
]
FREQ_FILE = "zh_full.txt"
FREQ_URL = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2016/zh/zh_full.txt"
GOOGLE_IT = ("https://inputtools.google.com/request?text={q}&itc=zh-t-i0-pinyin"
               "&num={n}&cp=0&cs=1&ie=utf-8&oe=utf-8&app=jsapi")

T9 = {}
for d, letters in {"2": "abc", "3": "def", "4": "ghi", "5": "jkl",
                    "6": "mno", "7": "pqrs", "8": "tuv", "9": "wxyz"}.items():
    for ch in letters:
        T9[ch] = d

def digit_str(s):
    return "".join(T9.get(c, "?") for c in s)

def fetch(url, timeout=30):
    t0 = time.time()
    req = urllib.request.Request(url, headers={"User-Agent": "VoiceIME-M2-verify/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read()
        return body, time.time() - t0, r.status

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

# ---------- Phase A: download ----------
print("=== Phase A: download dicts + freq list ===", flush=True)
for fname, url, kind in FILES:
    path = os.path.join(HERE, fname)
    if os.path.exists(path):
        print(f"{fname} already present: {os.path.getsize(path)} bytes", flush=True)
    else:
        body, dt, st = fetch(url)
        open(path, "wb").write(body)
        print(f"{fname} downloaded {len(body)} bytes status={st} in {dt:.2f}s", flush=True)
path = os.path.join(HERE, FREQ_FILE)
if os.path.exists(path):
    print(f"{FREQ_FILE} already present: {os.path.getsize(path)} bytes", flush=True)
else:
    body, dt, st = fetch(FREQ_URL)
    open(path, "wb").write(body)
    print(f"{FREQ_FILE} downloaded {len(body)} bytes status={st} in {dt:.2f}s", flush=True)

# ---------- Parse ----------
print("=== Parse dicts + freq ===", flush=True)
t0 = time.time()
word_by_pinyin = defaultdict(list)   # 'nihao' -> [(freq_score, norm_weight, order, word)]
char_by_syl = defaultdict(list)
syl_set = set()
order = 0
stats = {}
for fname, url, kind in FILES:
    path = os.path.join(HERE, fname)
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
        if len(syls) == 1 and len(word) == 1:
            char_by_syl[syls[0]].append((norm, order, word))
        order += 1
    with open(path, "rb") as f:
        gz = len(gzip.compress(f.read(), compresslevel=9))
    stats[fname] = {"entries": len(rows), "bytes": os.path.getsize(path), "gzip_bytes": gz}
    print(f"{fname}: entries={len(rows)} bytes={os.path.getsize(path)} gzip={gz}", flush=True)

freq = {}
with open(os.path.join(HERE, FREQ_FILE), encoding="utf-8", errors="replace") as f:
    for line in f:
        p = line.split()
        if len(p) >= 2:
            try:
                freq[p[0]] = int(p[1])
            except ValueError:
                pass
print(f"freq list: {len(freq)} words", flush=True)

def score(word, norm_w):
    """(freq_log, norm_weight): freq list first, dict weight tiebreak."""
    c = freq.get(word)
    return (math.log10(c) if c else -1.0, norm_w)

for k in word_by_pinyin:
    word_by_pinyin[k] = [(score(w, nw)[0], nw, o, w) for nw, o, w in word_by_pinyin[k]]
    word_by_pinyin[k].sort(key=lambda t: (-t[0], -t[1], t[2]))
for k in char_by_syl:
    char_by_syl[k] = [(score(w, nw)[0], nw, o, w) for nw, o, w in char_by_syl[k]]
    char_by_syl[k].sort(key=lambda t: (-t[0], -t[1], t[2]))
parse_s = time.time() - t0
print(f"merged: distinct_pinyin_keys={len(word_by_pinyin)} syllables={len(syl_set)} parse_time={parse_s:.2f}s", flush=True)

syl_by_digits = defaultdict(list)
for s in syl_set:
    d = digit_str(s)
    if "?" not in d:
        syl_by_digits[d].append(s)

def segment(digits, cap=200):
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
            if chunk in syl_by_digits:
                for s in sorted(syl_by_digits[chunk]):
                    for rest in rec(i + L):
                        out.append([s] + rest)
                        if len(out) >= cap:
                            memo[i] = out
                            return out
        memo[i] = out
        return out
    return rec(0)

def candidates_for(digits, topn=10):
    """Collect hits from ALL segmentations, rank globally by freq then dict weight."""
    t0 = time.time()
    segs = segment(digits)
    best = {}
    for seg in segs:
        joined = "".join(seg)
        for fs, nw, _, w in word_by_pinyin.get(joined, []):
            if w not in best or (fs, nw) > (best[w][0], best[w][1]):
                best[w] = (fs, nw, " ".join(seg))
    ranked = sorted(best.items(), key=lambda kv: (-kv[1][0], -kv[1][1]))
    cands = [{"word": w, "pinyin": p, "freq_log": round(fs, 2)}
             for w, (fs, _, p) in ranked[:topn]]
    if len(cands) < 3 and segs:
        seg = segs[0]
        per = [[w for _, _, _, w in char_by_syl.get(s, [])[:3]] for s in seg]
        cands.append({"per_syllable_chars": per, "segmentation": seg})
    return {"digits": digits, "n_segmentations": len(segs),
            "segmentations": [" ".join(s) for s in segs[:8]],
            "candidates": cands, "n_all_hits": len(ranked),
            "all_ranked": [w for w, _ in ranked[:100]],
            "ms": round((time.time() - t0) * 1000, 2)}

# precomputed digit index for prefix search (what the app should build once)
t0 = time.time()
prefix_index = []
for key, lst in word_by_pinyin.items():
    fs, nw, _, w = lst[0]
    prefix_index.append((digit_str(key), fs, nw, w, key))
prefix_index.sort(key=lambda t: (-t[1], -t[2]))
print(f"prefix index built: {len(prefix_index)} keys in {time.time() - t0:.2f}s", flush=True)

def prefix_candidates(digits, topn=10):
    t0 = time.time()
    hits = [t for t in prefix_index if t[0].startswith(digits)][:topn]
    n = sum(1 for t in prefix_index if t[0].startswith(digits))
    return {"digits": digits, "n_prefix_hits": n,
            "top": [{"word": w, "pinyin": p} for _, _, _, w, p in hits],
            "ms": round((time.time() - t0) * 1000, 2)}

# ---------- Phase B: LOCAL-ONLY tests ----------
print("\n=== Phase B: LOCAL-ONLY T9 tests (no network in this phase) ===", flush=True)
TESTS = [
    ("64426", "nihao (ni hao)", ["\u4f60\u597d"]),
    ("64646", "task example: NOT nihao keys (nihao=64426)", []),
    ("94664486", "zhongguo", ["\u4e2d\u56fd"]),
    ("943943", "xiexie", ["\u8c22\u8c22"]),
    ("9245426", "zaijian", ["\u518d\u89c1"]),
    ("96", "wo", ["\u6211"]),
    ("926", "wan", ["\u665a", "\u73a9"]),
    ("7484", "shui", ["\u6c34"]),
    ("5426", "lian", ["\u7ec3"]),
    ("426", "han", ["\u6c49"]),
    ("84", "ti", ["\u4f53"]),
    ("2", "a", ["\u554a"]),
    ("743663", "shenme (shen me)", ["\u4ec0\u4e48"]),
    ("94664936", "zhongwen", ["\u4e2d\u6587"]),
    ("542694", "lianxi", ["\u7ec3\u4e60"]),
    ("96636", "women", ["\u6211\u4eec"]),
]
local_results = {"dicts": stats, "freq_words": len(freq),
                 "merged_keys": len(word_by_pinyin),
                 "syllables": len(syl_set), "parse_s": round(parse_s, 2),
                 "tests": [], "prefix_tests": []}
for digits, note, expect in TESTS:
    r = candidates_for(digits)
    got = [c.get("word") for c in r["candidates"] if "word" in c]
    ranks = {}
    for e in expect:
        ranks[e] = (r["all_ranked"].index(e) + 1) if e in r["all_ranked"] else None
    r["expected_rank_in_top100"] = ranks
    ok = all(v is not None for v in ranks.values()) if expect else None
    r["note"] = note
    r["expected"] = expect
    r["expected_found"] = ok
    local_results["tests"].append(r)
    print(f"[{digits}] ({note}) segs={r['n_segmentations']} ms={r['ms']} "
          f"cands={got[:10]} expected={expect} ranks={ranks} found={ok}", flush=True)
for digits in ("644", "96", "94"):
    r = prefix_candidates(digits)
    local_results["prefix_tests"].append(r)
    print(f"[prefix {digits}] hits={r['n_prefix_hits']} ms={r['ms']} "
          f"top={[t['word'] for t in r['top']]}", flush=True)
with open(os.path.join(HERE, "local_results.json"), "w", encoding="utf-8") as f:
    json.dump(local_results, f, ensure_ascii=False, indent=2)
print("wrote local_results.json", flush=True)

# ---------- Phase C: online tests (Google Input Tools, no key) ----------
print("\n=== Phase C: ONLINE tests (Google Input Tools, no key) ===", flush=True)
ONLINE_Q = [("nihao", "\u4f60\u597d"), ("zhongguo", "\u4e2d\u56fd"),
            ("xiexie", "\u8c22\u8c22"), ("zaijian", "\u518d\u89c1"),
            ("wo", "\u6211"), ("nih", None), ("zhongg", None)]
online_results = {"api": "Google Input Tools (unofficial, no key)", "rounds": []}
for q, expect in ONLINE_Q:
    for rnd in range(2):
        try:
            body, dt, st = fetch(GOOGLE_IT.format(q=q, n=10))
            data = json.loads(body.decode("utf-8"))
            ok_flag = data[0] == "SUCCESS"
            cands = data[1][0][1] if ok_flag and data[1] else []
            found = (expect in cands) if expect else None
            rec = {"q": q, "round": rnd + 1, "status": st, "latency_s": round(dt, 3),
                   "success": ok_flag, "top_candidates": cands[:10],
                   "expected": expect, "expected_found": found}
        except Exception as e:
            rec = {"q": q, "round": rnd + 1, "error": repr(e)}
        online_results["rounds"].append(rec)
        print(rec, flush=True)
with open(os.path.join(HERE, "online_results.json"), "w", encoding="utf-8") as f:
    json.dump(online_results, f, ensure_ascii=False, indent=2)
print("wrote online_results.json", flush=True)
print("\nALL DONE", flush=True)
