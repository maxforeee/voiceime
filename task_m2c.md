# Task M2c — VoiceIME M2 shell: build all 6 chunks + GitHub Actions workflow, commit locally

## ONE GOAL (the only success criterion)

Build the complete VoiceIME v1 input-method app (native Kotlin) per task_m2b.md chunks 1-6, plus a GitHub Actions workflow that builds the debug APK, and commit everything locally on main. A later step (not you) pushes to GitHub and the Actions build is the build verification.

## OUTPUT-FIRST RULE (read this first)

- Make your FIRST code edit within about 15 tool calls. Reading without writing is not progress. Long silent exploration with no visible action is failure.
- Work chunk by chunk in the order in task_m2b.md (1 skeleton, 2 T9 keyboard + candidate bar, 3 lexicon engine, 4 online suggestions, 5 voice keyboard + ASR + toggle, 6 clipboard + polish). Finish a chunk, commit it locally, then move on. Do not start a later chunk before the current one's code is written.
- Read ONLY the files listed in task_m2b.md "Files you may read" plus m2_verify/t9_verify.py and m1_verify/run_m1.py (ASR protocol reference). No broad repo sweeps.
- Do NOT touch RoutePass, LyricSync, or any other project. Do NOT start voice editing / correction (v2/M4). Do NOT push to GitHub (commit locally only). Do NOT install an Android SDK.
- NEVER write any API key, token, or secret into code, config files, task files, reports, or git. The Doubao speech key is read ONLY from env var DOUBAO_API_KEY_FROM_CONFIG at build time (into BuildConfig). Never print it, never copy it, never look for its value.

## OVERRIDES vs task_m2b.md (these replace the conflicting lines there)

1. BUILD VERIFICATION: this Box has NO Android SDK and you must NOT try to install one (no disk/RAM for it, and it wastes the session). `./gradlew assembleDebug` will fail here — that is expected, do NOT chase it. Instead write `.github/workflows/android.yml` (JDK 17, Gradle, `./gradlew assembleDebug`, upload `app/build/outputs/apk/debug/app-debug.apk` as artifact `voiceime-debug-apk`) as part of Chunk 1. The GitHub Actions run is the real build check; a later step pushes and reads its log. If you can run a cheap syntax sanity check without an SDK (e.g. kotlinc is NOT installed either — do not install it), skip it; rely on careful code + the Actions build.
2. LEXICON DB: run your build-time converter script ON THIS BOX against m2_verify/luna_pinyin.dict.yaml + m2_verify/ice_base.dict.yaml + m2_verify/zh_full.txt, produce a compact SQLite DB (target a few MB) bundled in the app (assets/ or res/raw). COMMIT the compact DB. Do NOT commit the raw 22MB YAML dicts or zh_full.txt — add them to .gitignore. The Actions build must be self-contained: it must NOT need the raw dict files.
3. PUSH: the parent decision is to push to GitHub main and build via Actions. You commit locally only; pushing happens after your run. Your m2b_report.md must list the exact local commit hashes.

## Spec recap (from task_m2b.md — follow it exactly, do not re-derive)

- Package com.voiceime, minSdk 28, targetSdk 34, app name is an internal name only, NEVER call it "DouBao Input Method" / "\u8c46\u5305\u8f93\u5165\u6cd5" (trademark). T9 keyboard ONLY (no 26-key). The ONLY voice entry is the top-right toggle (no voice key on the keyboard or anywhere else). Big voice keyboard: one large mic button, BOTH long-press (hold to talk, release to stop) AND tap (tap to start, tap again to stop). Clipboard keeps last 10 items, tap to paste, plus clear. Gboard default light colors. Fast / simple / responsive is the bottom line.
- Lexicon: T9 map 2=abc 3=def 4=ghi 5=jkl 6=mno 7=pqrs 8=tuv 9=wxyz. DP over the 424-syllable table, enumerate ALL valid segmentations (cap 200), look up every segmentation, rank globally by word frequency then dict weight, top 10, scrollable candidate bar, dedupe by word, remember tapped candidates and rank them higher next time. Do NOT take only the first 20 segmentations. nihao = 64426 (NOT 64646 — 64646 yields no common candidate, never use it as a test case). Mandatory regression cases: 64426->\u4f60\u597d, 94664486->\u4e2d\u56fd, 943943->\u8c22\u8c22, 9245426->\u518d\u89c1, 743663->\u4ec0\u4e48, 542694->\u7ec3\u4e60, 96636->\u6211\u4eec, 96->\u6211.
- Online: Google Input Tools https://inputtools.google.com/request?text=<pinyin>&itc=zh-t-i0-pinyin&num=10&cp=0&cs=1&ie=utf-8&oe=utf-8&app=jsapi, debounce ~100ms, 300ms timeout, merge + dedupe, local first, NEVER block typing, send only the pinyin string.
- ASR: Doubao streaming, endpoint wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async, resource volc.seedasr.sauc.duration, header X-Api-Key from BuildConfig (env DOUBAO_API_KEY_FROM_CONFIG at build time). Prefer the official Doubao Android SDK (SpeechEngine) if it resolves from Maven; if it does not resolve, port the M1 WebSocket protocol (see m1_verify/run_m1.py + m1_report.md) to Kotlin with OkHttp. AudioRecord 16kHz/16-bit/mono PCM. Interim results update the display live; final results commit once via commitText() with no missing/duplicated words. If the key is empty at build time, voice mode must show a clear "voice not configured" message and typing must keep working — never crash. Handle no-network / no-permission / recognition failure with a message, never a crash or a frozen text field. Request RECORD_AUDIO permission properly. Switching modes stops recording first. Reserve a visible but non-working "edit" entry position for v2 (label it as coming later, do not implement editing).
- License note for your report: luna-pinyin LGPLv3, rime-ice GPLv3, frequency list from OpenSubtitles with no explicit license. The compact DB is derived from them — flag in m2b_report.md that license compatibility must be confirmed before any release packaging. The frequency file is build-time only and must NOT ship in the APK.

## Deliverables (in /workspace/voiceime, commit locally on main)

- Full Android project source (Gradle Kotlin DSL or Groovy — pick one, be consistent).
- .github/workflows/android.yml as specified above.
- Compact lexicon SQLite DB committed; raw dicts gitignored.
- m2b_report.md: chunk-by-chunk what was built, which files, what could NOT be verified on this Box (no SDK, no device — state this plainly), the 8 regression cases with the expected results from your converter/DB test on the Box (run the segmentation logic in Python/Kotlin-offline or a JVM-free check against the built DB and record the outcome), online/offline behavior design, voice end-to-end status (cannot be tested on this Box — say so), license note, and the local commit hashes.
- Local commits on main (one per chunk if practical, else fewer — but commit at least once per finished chunk so work is not lost).

## Hard constraints (violating these fails the task)

- One goal only. First code edit within ~15 tool calls. No broad sweeps.
- No voice editing / correction (v2/M4). No 26-key keyboard. No emoji/GIF/sticker, no translation, no themes, no account system, no cloud sync, no handwriting, no offline speech model.
- No separate voice key anywhere. The top-right toggle is the only voice entry.
- No API key, token, or secret anywhere in code, config, docs, or git history. Scan before committing (grep for the env var name appearing only as a BuildConfig reference, never a value).
- Never present a plan as running or complete until a build log, test output, or file proves it. If the Actions build has not run yet, say "not yet built on Actions" — do not claim it passes.
