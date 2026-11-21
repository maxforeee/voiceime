# Task M2b — VoiceIME shell: T9 typing keyboard + big voice keyboard + toggle + clipboard + streaming ASR

## ONE GOAL (the only success criterion)

Build an installable Android APK (native Kotlin, no cross-platform framework) that works as a real system input method: a T9 pinyin typing keyboard with a candidate bar, a big voice keyboard, one single toggle in the top-right corner to switch between them, clipboard history (last 10 items), and streaming speech recognition whose results are committed to the text field. Typing must feel instant: keys respond immediately, candidates appear without waiting for the network.

## OUTPUT-FIRST RULE (read this first)

- Make your FIRST code edit within about 15 tool calls. Reading without writing is not progress. Long silent exploration with no visible action is failure.
- Work in small chunks, one chunk at a time, in the order below. Finish a chunk, build it, verify it, then move to the next. Do not start a later chunk before the current one builds.
- Never claim a chunk is done from your own self-check alone. Every chunk ends with a real build or a real run: `gradlew` output, a test run, or an installed APK. If the build fails, fix it before moving on.
- Read at most the files listed in "Files you may read". Do not run broad searches across the whole repo.
- Do NOT touch RoutePass, LyricSync, or any other project. Do NOT start voice editing / voice correction (that is v2 / M4). Do NOT push to GitHub. Commit locally on main only; pushing is decided later.
- NEVER write any API key, token, or secret into code, config files, task files, or git. The speech API key is already configured on this machine and is read only from the environment variable DOUBAO_API_KEY_FROM_CONFIG. Do not print it, do not copy it, do not look for its value.

## Context (verified facts — do not re-verify, build on them)

- Spec: `实施书v1.8.md` in this repo (v1.8, 2026-11-21). Hard requirements: T9 keyboard ONLY (no 26-key); the ONLY voice entry is the top-right toggle (no voice key on the keyboard or anywhere else); big voice keyboard with one large mic button supporting BOTH long-press (hold to talk, release to stop) and tap (tap to start, tap again to stop); clipboard keeps the last 10 items; Gboard default light colors; fast, simple, responsive is the bottom line, not a bonus.
- M1 PASSED (see `m1_report.md`): Doubao streaming ASR works — CER 0.00% on 6/6 sentences, median final-result latency 0.37s. Endpoint `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`, resource `volc.seedasr.sauc.duration`, auth header `X-Api-Key` from env var DOUBAO_API_KEY_FROM_CONFIG. Official Android SDK (SpeechEngine) is preferred over hand-built WebSocket protocol. Audio: AudioRecord, 16kHz, 16-bit, mono, PCM, streamed.
- M2 lexicon verification PASSED (see `m2_lexicon_report.md`, evidence in `m2_verify/`). The lexicon route is settled — use it exactly as below, do not invent another one:
  - Local dictionary files already in `m2_verify/`: `luna_pinyin.dict.yaml` (70,732 entries, mostly single characters) + `ice_base.dict.yaml` (543,423 entries, mostly phrases). Merged: 464,180 distinct pinyin keys, 424 syllables. `zh_full.txt` (428,719 words with corpus frequencies) is the ranking source — use it at BUILD time only to bake rankings, do NOT ship the file in the APK.
  - T9 segmentation algorithm: T9 digit map (2=abc, 3=def, 4=ghi, 5=jkl, 6=mno, 7=pqrs, 8=tuv, 9=wxyz). Enumerate ALL valid segmentations of the digit string with DP over the 424-syllable table (cap 200; real max seen is 72). Look up every segmentation in the pinyin->words table, collect ALL hits, rank globally by word frequency (then dict weight), take top 10. Do NOT take only the first 20 segmentations — that demonstrably drops real words ("zhongguo" / "zaijian" were lost that way).
  - Online suggestions: Google Input Tools, no key, no account: `https://inputtools.google.com/request?text=<pinyin>&itc=zh-t-i0-pinyin&num=10&cp=0&cs=1&ie=utf-8&oe=utf-8&app=jsapi`. Verified from this machine: 14/14 rounds SUCCESS, latency 0.11-0.23s, correct first candidate every time, and partial pinyin works ("nih" -> "你好", "zhongg" -> "中国"). It is UNOFFICIAL with no SLA: debounce ~100ms after a keypress, drop the request if it does not answer within 300ms, and NEVER let the keyboard wait for the network — local candidates show first, online results merge in and are deduplicated. Send only the pinyin string, never committed sentences.
  - Build-time conversion: convert the two YAML dicts into a compact SQLite / binary index at build time (raw text is ~22MB, gzip ~6.7MB — too big to ship raw; trim to frequent words if needed, target a few MB). A naive full-table prefix scan costs ~280ms — the app MUST use a prebuilt digit-string index (trie / SQLite), never scan at keypress time. Segmentation itself costs 0.02-0.97ms per query.
  - Deduplicate candidates: the same word can appear under several readings (e.g. "这" as zhe/zhei) — deduplicate by word.
  - Correction: the example key string "64646" is NOT nihao. nihao is "64426" (n=6,i=4,h=4,a=2,o=6). "64646" yields no common candidate. Do not use it as a test case.
  - Mandatory regression cases (must pass in the finished APK): 64426->"你好", 94664486->"中国", 943943->"谢谢", 9245426->"再见", 743663->"什么", 542694->"练习", 96636->"我们", 96->"我". Single-syllable keys are ambiguous by nature (e.g. 5426: "练" ranks 22nd, "汉" 13th on 426) — the candidate bar MUST scroll / page, and a tapped candidate is remembered and ranked higher next time.
- License note: luna-pinyin is LGPLv3, rime-ice is GPLv3, the frequency list comes from OpenSubtitles data with no explicit license in its README. For this personal test APK that is fine; flag it in your final report if you ship the dict files, so the release decision can check compatibility. Do not add any new third-party dependency without saying so in your report.

## Files you may read (beyond this task file)

- `实施书v1.8.md` (the spec — read sections 1-3, 5, 8 first)
- `m2_lexicon_report.md` (the verified lexicon plan)
- `m2_verify/t9_verify.py` (the reference implementation of segmentation + ranking — port its logic to Kotlin, do not copy Python into the app)
- `m1_report.md` (M1 ASR findings: endpoint, SDK, latency)

## Chunks (do them in this order, one at a time)

### Chunk 1 — Project skeleton + input method shell
- Create the Android project (native Kotlin, minSdk 28, targetSdk 34, package `com.voiceime`), `InputMethodService` registered in the manifest with the input-method intent filter, so the keyboard appears in system Settings and can be set as default. App name: internal name only, do NOT call it "豆包输入法" (trademark). Just a bare shell that opens and closes without crashing; no keyboard UI yet. Verify: `./gradlew assembleDebug` succeeds.

### Chunk 2 — T9 typing keyboard + candidate bar
- Build the T9 keyboard UI (3x4 grid: 1, 2abc, 3def, 4ghi, 5jkl, 6mno, 7pqrs, 8tuv, 9wxyz, 0, delete, space/enter row) + candidate bar above it + middle/English and number/symbol switching. Tapping a candidate commits it via `InputConnection.commitText()`. Gboard default light colors. Keyboard height auto-adjusts. Verify: install the APK, set it as default, type 64426 in a notes app and see "你好" in the candidate bar; tapping it commits the text.

### Chunk 3 — Lexicon engine (local first)
- Write a small build-time converter (a script, not app code) that turns `m2_verify/luna_pinyin.dict.yaml` + `m2_verify/ice_base.dict.yaml` into a compact SQLite database bundled in the app, with rankings baked in from `m2_verify/zh_full.txt`. Port the `t9_verify.py` segmentation + ranking logic to Kotlin: enumerate all segmentations, global frequency ranking, top 10, scrollable candidate bar, dedupe by word, remember tapped candidates. Verify: the 8 mandatory regression cases above all show the expected word (in the scrollable list, not necessarily first for single-syllable keys); with the device offline (airplane mode), all of them still work from the local database alone.

### Chunk 4 — Online suggestions + fallback
- Add the Google Input Tools client exactly as specified (debounce ~100ms, 300ms timeout, merge + dedupe, local first, never block typing). Verify: with network on, typing "nih" suggests "你好"; with the request forced to time out (or network off mid-typing), typing stays instant and local candidates are unaffected.

### Chunk 5 — Big voice keyboard + streaming ASR + top-right toggle
- Top-right toggle button switches keyboard mode <-> voice mode and back, fast, without reloading the whole keyboard; no voice key anywhere else. Voice mode: one large mic button (long-press AND tap both work), live interim text + status display above it, a reserved (not yet working) "edit" entry position for v2. Wire AudioRecord (16kHz/16-bit/mono PCM) to Doubao streaming ASR per M1 (official Android SDK preferred, key only from env var DOUBAO_API_KEY_FROM_CONFIG at build time — never in code). Interim results update the display live; final results commit via `commitText()`. Handle interrupts: switching modes stops recording first; no-network / no-permission / recognition failure shows a message and never crashes or freezes the text field. Request RECORD_AUDIO permission properly. Verify: hold to talk and tap to talk both work end to end in a real text field; interim text keeps up with speech; final text is committed once, with no missing or duplicated words.

### Chunk 6 — Clipboard (last 10) + polish
- Clipboard history: last 10 copied items, tap to paste, plus clear. Check the full v1 acceptance list in spec section 8 (default IME, fast/responsive, toggle works, both voice modes, clipboard, Gboard colors, auto height, open/close, no crash on errors, simple UI with no ads or extra entries). Fix what fails; do not expand scope. Verify: `./gradlew assembleDebug` + install + a full pass of the section-8 checklist, reported as pass/fail per item with the evidence (which app / which text field / what happened).

## Deliverables (in this repo, do not push)

- The Android project source + a built debug APK under `app/build/outputs/apk/debug/`.
- `m2b_report.md`: what was built, chunk-by-chunk pass/fail with real evidence (build outputs, regression results for the 8 mandatory cases, online/offline behavior, voice end-to-end result), and anything that does not work yet.
- Commit locally on main when done. Do NOT push to GitHub.

## Hard constraints (repeat: violating these fails the task)

- One goal only. First code edit within ~15 tool calls. No broad repo sweeps.
- No voice editing / correction (v2/M4). No 26-key keyboard. No emoji/GIF/sticker, no translation, no themes, no account system, no cloud sync, no handwriting, no offline speech model.
- No separate voice key on the keyboard, candidate bar, space bar, or anywhere else. The top-right toggle is the only voice entry.
- No API key, token, or secret anywhere in code, config, docs, or git history.
- Never present a plan as running or complete until the build log, test output, or APK proves it.
