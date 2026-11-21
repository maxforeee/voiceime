#!/usr/bin/env python3
"""VoiceIME build-time English wordlist builder (NOT app code).

Builds two assets for the 9-key English engine:
  app/src/main/assets/english_words.txt      -- tier 1: wordfreq top 10,000
      (lexicons/en/common/wordfreq_en_top10000.tsv, Zipf order) + our
      proper nouns from lexicons/en/proper/** (single-token, lowercased)
      appended at the end (deduplicated). Line order = frequency rank.
  app/src/main/assets/english_words_rare.txt -- tier 2: dwyl english-words
      (lexicons/en/common/english_words_alpha.tsv), cleaned (lowercase a-z,
      length 2..15, not already in tier 1), each line
      "digit-signature<TAB>word", sorted by (signature, word) so the app
      can binary-search a digit prefix.
NOTE: first20hours/google-10000-english is NOT used (no license declared);
the collector excluded it and wordfreq top 10,000 replaces it.
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
LEX = os.path.join(ROOT, "lexicons")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets")

T9 = {}
for d, letters in {"2": "abc", "3": "def", "4": "ghi", "5": "jkl",
                   "6": "mno", "7": "pqrs", "8": "tuv", "9": "wxyz"}.items():
    for ch in letters:
        T9[ch] = d


def sig(word):
    return "".join(T9[c] for c in word)


def read_names(path):
    """Single-token lowercase alpha names from a 'name' TSV (one per line)."""
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            w = line.strip().lower()
            if w and w.isalpha() and " " not in w:
                out.append(w)
    return out


def main():
    tier1 = []
    seen = set()
    with open(os.path.join(LEX, "en/common/wordfreq_en_top10000.tsv"), encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if not parts or not parts[0]:
                continue
            w = parts[0].strip().lower()
            if w and w.isalpha() and w not in seen:
                seen.add(w)
                tier1.append(w)
    n_top = len(tier1)
    proper = 0
    for rel in ("en/proper/companies/ai_companies_en.tsv",
                "en/proper/companies/companies_en.tsv",
                "en/proper/places/places_en.tsv",
                "en/proper/people/first_names_en.tsv",
                "en/proper/people/last_names_en.tsv"):
        for w in read_names(os.path.join(LEX, rel)):
            if w not in seen:
                seen.add(w)
                tier1.append(w)
                proper += 1
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "english_words.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(tier1) + "\n")
    rare = set()
    with open(os.path.join(LEX, "en/common/english_words_alpha.tsv"), encoding="utf-8") as f:
        for line in f:
            w = line.strip().lower()
            if w.isalpha() and 2 <= len(w) <= 15 and w not in seen:
                rare.add(w)
    rows = sorted((sig(w), w) for w in rare)
    with open(os.path.join(OUT_DIR, "english_words_rare.txt"), "w", encoding="utf-8") as f:
        for s, w in rows:
            f.write(f"{s}\t{w}\n")
    print(f"english_words.txt: {len(tier1)} words (top10000={n_top} + proper={proper})")
    print(f"english_words_rare.txt: {len(rows)} words")
    print(f"sizes: {os.path.getsize(os.path.join(OUT_DIR, 'english_words.txt'))} / "
          f"{os.path.getsize(os.path.join(OUT_DIR, 'english_words_rare.txt'))} bytes")


if __name__ == "__main__":
    main()
