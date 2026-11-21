# M2b/M2c Report — VoiceIME v1 shell: all 6 chunks built, committed locally on main

- Date: 2026-11-21
- Status: **All 6 chunks of task_m2b.md implemented and committed locally (one commit per chunk). NOT pushed. NOT yet built on Actions.**
- Build verification: this Box has **no Android SDK and no device** (and per task_m2c.md none was installed). `./gradlew assembleDebug` was therefore **not run here**; the GitHub Actions workflow (`.github/workflows/android.yml`, JDK 17 + Gradle 8.7 wrapper + `assembleDebug` + artifact `voiceime-debug-apk`) is the real build check and **has not run yet** — no claim is made that it passes. A later step pushes and reads the Actions log.
- App name is the internal name **语音输入法** only. Never "豆包输入法" (trademark). Package `com.voiceime`, minSdk 28, targetSdk 34, native Kotlin, Views (no cross-platform framework, no Compose).

---

## 1. Local commits (main, not pushed)

| Chunk | Commit | Subject |
|---|---|---|
| 1 | `83b168efc8557d84efc7d5ecbdb1c223a58926b6` | Android IME skeleton + Actions debug-APK workflow |
| 2 | `d51327b997bcafe848fd5e8a2951b7c2cc497b4c` | T9 keyboard + scrollable candidate bar + 中/英 + 数字/符号 |
| 3 | `6e984e0131270c906171d0646420b7a6b8873dde` | Lexicon engine: compact SQLite DB + Kotlin segmentation/ranking |
| 4 | `5f0c91efa76174629a640eb03f31f0715ed83c7d` | Online suggestions (Google Input Tools) + local-first fallback |
| 5 | `a2777a887043edbac8a5f9b1b24b6b0ea840a37e` | Big voice keyboard + Doubao streaming ASR + top-right toggle |
| 6 | `5515f130a8240943230104e7fe9494920606c677` | Clipboard (last 10) + polish |
| report | (this file's commit, see `git log`) | m2b_report.md |

## 2. Chunk-by-chunk: what was built, which files

### Chunk 1 — skeleton + CI
- Gradle Kotlin DSL: `settings.gradle.kts`, `build.gradle.kts` (AGP 8.5.2, Kotlin 1.9.24), `gradle.properties`, `app/build.gradle.kts`, official Gradle 8.7 wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*` — jar downloaded from the official gradle/gradle v8.7.0 tag, sha256 `cb0da6751c2b753a16ac168bb354870ebb1e162e9083f116729cec9c781156b8`).
- `app/src/main/AndroidManifest.xml`: `VoiceImeService` with `BIND_INPUT_METHOD` + `android.view.InputMethod` intent filter + `@xml/method` metadata → appears in system IME settings. Adaptive launcher icon, internal label.
- `.github/workflows/android.yml`: on push/PR to main + manual dispatch; JDK 17 (temurin), `gradle/actions/setup-gradle@v4`, `./gradlew assembleDebug --no-daemon --stacktrace`, uploads `app/build/outputs/apk/debug/app-debug.apk` as artifact **voiceime-debug-apk** (`if-no-files-found: error`). The speech key is passed only as the optional repo secret `DOUBAO_API_KEY_FROM_CONFIG` into the build env; never echoed.
- `.gitignore`: build outputs, IDE files, and the **raw dict sources** (`m2_verify/luna_pinyin.dict.yaml`, `m2_verify/ice_base.dict.yaml`, `m2_verify/zh_full.txt`). Verified: 0 m2_verify files tracked.
- Verified on Box: file layout, wrapper download, gitignore behavior. **Not verified: compilation** (no SDK).

### Chunk 2 — T9 keyboard + candidate bar
- `ui/T9KeyboardView.kt`: programmatic Gboard-light grid (no inflation): rows `1 / 2ABC / 3DEF`, `4GHI / 5JKL / 6MNO`, `7PQRS / 8TUV / 9WXYZ`, `中/英 / 0 / ⌫`, `符号 / 空格 / 回车`; symbol/number page `1-9`, `，0。`, `？！…`, `返回 / 空格 / 回车`. Row height auto-scales with screen size (clamped 42–58dp, shorter in landscape). Pressed-state feedback via StateListDrawable.
- `ui/CandidateBar.kt`: composing-pinyin label + horizontally scrollable candidates (top one highlighted blue), tap to commit. `ui/UiUtils.kt`: dp + key backgrounds. `Candidate.kt`: `Candidate` / `CandidateResult` / `CandidateProvider`.
- `VoiceImeService.kt`: digit buffer (cap 20) → candidates; space commits top candidate (else raw digits); enter commits raw digits or performs the editor action; delete edits buffer or sends KEYCODE_DEL; 中/英 switches to English **multitap** (2=abc cycling, 900ms timeout, composing text); key 1 commits `，`, key 0 commits `0` in Chinese mode; number/phone fields open on the digits page (Chunk 6 polish). `onEvaluateFullscreenMode()=false`.
- Colors (`res/values/colors.xml`): Gboard default light approximation — canvas `#ECEFF1`, keys `#FFFFFF`, function keys `#E8EAED`, text `#202124`/`#5F6368`, accent `#1A73E8`. Real-Gboard side-by-side comparison is an M3 device item.
- **Not verified on Box: no install/run possible.**

### Chunk 3 — lexicon engine (local first)
- `tools/build_lexicon.py` (build-time converter, NOT app code): parses both YAML dicts exactly like the verified `t9_verify.py` (ü/ǖ→v, luna `%` weights /100, ice int weights /file-max), bakes FrequencyWords `zh_full.txt` rankings, writes **`app/src/main/assets/lexicon.db` (6.15 MB, committed)**. Raw 22MB dicts + freq file are gitignored and never shipped; the Actions build needs none of them.
  - Trim policy (to hit the few-MB target): keep **all single-syllable single chars** + every word with corpus frequency ≥ 1. 121,492 words / 59,179 pinyin keys / 131,207 entries kept; 482,948 rare zero-frequency phrase entries dropped. Rare phrases fall back to per-syllable characters in the engine.
  - Schema: `word(id,w,f)`, `pykey(p, ws BLOB)` WITHOUT ROWID (rank-ordered varint wordId + u16 weight), `prefix(d,wid,s)` + index (top-60k frequent words' digit strings, for partial input), `syl(d,s)` (424 syllables), `meta`. All lookups are primary-key / index-range reads — **no keypress-time table scan**.
- `lexicon/Lexicon.kt`: exact Kotlin port of the verified algorithm — DP enumerates **ALL** segmentations (cap 200, never "first 20"), looks up every segmentation, ranks globally by (log10 freq, dict weight), dedupes by word, returns **top 50** for the scrollable bar (so 练 #22 / 汉 #13 are reachable), appends deduped digit-prefix hits, per-syllable char fallback when a rare phrase was trimmed. Tapped candidates remembered in SharedPreferences (cap 1000) and boosted (+0.5 log10 per tap, max 8 taps). DB copied from assets once, schema-version checked; any failure degrades to raw-digit typing, never a crash.
- **Verified on Box (real run, `tools/test_lexicon.py` against the built DB, results in `tools/lexicon_test_results.json`): 16/16 PASS**, including all 8 mandatory cases; 0.3–3.7 ms per query. Rankings match the M2 full-dict verification exactly:

| digits | expected | rank in built-DB list | result |
|---|---|---|---|
| 64426 | 你好 | 1 | PASS |
| 94664486 | 中国 | 1 | PASS |
| 943943 | 谢谢 | 1 | PASS |
| 9245426 | 再见 | 1 | PASS |
| 743663 | 什么 | 1 | PASS |
| 542694 | 练习 | 4 | PASS |
| 96636 | 我们 | 1 | PASS |
| 96 | 我 | 1 | PASS |
| 926 | 玩 / 晚 | 2 / 6 | PASS |
| 7484 | 水 | 5 | PASS |
| 5426 | 练 | 22 | PASS |
| 426 | 汉 | 13 | PASS |
| 84 | 体 | 7 | PASS |
| 2 | 啊 | 1 | PASS |
| 94664936 | 中文 | 1 | PASS |

  `64646` is NOT nihao and was not used as a test case (informational: with the prefix index it surfaces 明明/明年 etc.).
- **Not verified on Box: the Kotlin engine itself running on a device** (no JVM/kotlinc/SDK here). The Python tester mirrors the Kotlin logic 1:1 against the same DB; the Kotlin code path awaits the Actions build + M3.

### Chunk 4 — online suggestions + fallback
- `online/GoogleSuggestions.kt`: OkHttp, exact verified URL (`itc=zh-t-i0-pinyin&num=10&cp=0&cs=1&ie=utf-8&oe=utf-8&app=jsapi`), **callTimeout 300ms**, `retryOnConnectionFailure(false)`, silent failure. Response parsed as `["SUCCESS",[[input,[cands]]]]`.
- Service: **100ms debounce** after each keypress; previous request cancelled; local candidates render synchronously first and are never replaced by a waiting state; online words merge **in front**, deduped by word; stale responses (buffer changed) discarded; only the pinyin string is sent (first full segmentation; else longest segmentable prefix + best-effort letters), never committed sentences. Offline / timeout / airplane mode: typing stays instant on the local DB.
- **Verified on Box: live endpoint re-check** — `nihao` → 你好 first; partial `nih` → 你好/你会/你还; response shape matches the parser. **Not verified: in-app merge behavior on a device.**

### Chunk 5 — big voice keyboard + streaming ASR + toggle
- **SDK decision:** the brief prefers the official SpeechEngine SDK *if it resolves from Maven*. Probed from this Box: ByteDance Volcengine repo path returned empty and Maven Central search for `speechengine` returned **0 hits** — exact coordinates/version could not be confirmed, and an unresolvable dependency would break the Actions build. Per the brief's explicit fallback, the **M1-verified WebSocket protocol was ported to Kotlin with OkHttp** (`asr/AsrProtocol.kt`: v3 binary framing — full client request, audio packets, negative-sequence last packet, gzip responses; `asr/DoubaoAsrClient.kt`: endpoint `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`, resource `volc.seedasr.sauc.duration`, headers `X-Api-Key` / `X-Api-Resource-Id` / `X-Api-Connect-Id`; request payload identical to M1: bigmodel, enable_punc, no ITN, show_utterances, result_type full).
- `asr/AudioCapture.kt`: AudioRecord **16kHz / 16-bit / mono PCM**, 100ms chunks (3200B) on a dedicated thread; blocking read paces at real time; stop joins the thread and releases the mic.
- `ui/VoiceKeyboardView.kt`: one large (132dp) mic button — **long-press hold-to-talk (350ms threshold, release stops) AND tap-toggle (tap starts, tap stops)**; live interim text (scrollable) + status line above; red while recording; bottom row: 键盘 (back) + **disabled, visible "修改 · v2 即将推出" reserved entry** (no editing implemented, per v1 scope). No voice key anywhere else.
- Toggle: fixed **top-right** button (mic ⇄ keyboard icon), flips pre-built views (no reload). Switching modes, finishing input, or hiding the window **stops recording first**; a dropped connection without a final packet salvages the last interim; recognition failures show a message and commit nothing.
- Commit semantics: interim text only updates the display; the server's cumulative **final is committed exactly once** via `commitText()` (`committed` guard + session-id invalidation of stale callbacks) — no missing/duplicated words by construction. 8s final-result timeout guard.
- Permissions/interrupts: RECORD_AUDIO requested via transparent `PermissionActivity` (IMEs cannot request permissions directly); no-network check before connecting (`ConnectivityManager`); empty build-time key → clear **"语音未配置"** message, mic disabled, **typing unaffected**; every failure path is caught and messaged, never a crash or frozen field.
- Key hygiene: `BuildConfig.DOUBAO_API_KEY` injected **only** from env `DOUBAO_API_KEY_FROM_CONFIG` at build time (`app/build.gradle.kts`), escaped, never logged/printed/written; in Actions it comes from the optional repo secret of the same name.
- **Voice end-to-end status: NOT testable on this Box** — no microphone, no Android device, no SDK. M1 proved the protocol/endpoint/accuracy (CER 0.00%, median final latency 0.37s) from this Box with TTS audio; the Kotlin port is line-by-line from that harness, but hold/tap-to-talk, interim pacing, and commit-once on a real text field await the Actions-built APK + M3 device testing.

### Chunk 6 — clipboard + polish
- `clipboard/ClipboardHistory.kt`: last **10** copied texts, newest first, deduped, persisted locally (SharedPreferences; no cloud/account). `ui/ClipboardPanelView.kt`: list (2-line preview), **tap to paste** (`commitText`), **清空** and **关闭**. Entry: clipboard icon in the top bar **left of** the toggle (toggle remains the top-right corner). Opening the panel stops recording first; panel closes on mode switch / new input field.
- `ClipboardManager.OnPrimaryClipChangedListener` registered in `onCreate`, removed in `onDestroy`. Android 10+ only allows clipboard reads for the focused default IME — reads are attempted on clip-change and silently skipped when restricted (documented limitation; history fills while VoiceIME is the active keyboard).
- Polish: number/phone fields auto-open the digits page; static sanity checks run on Box (14 Kotlin files, balanced delimiters, no secret-like literals).
- **Section-8 acceptance checklist (spec 实施书v1.8 §8) — device items cannot run on this Box:**

| Item | Code status | Verified on Box? |
|---|---|---|
| Set as default IME, any text field | manifest + service registered | No device — M3 |
| Fast popup / instant keys / instant candidates | programmatic views, indexed DB (0.3–3.7ms measured), no blocking network | DB latency yes; feel — M3 |
| Top-right toggle only, fast switch | implemented, pre-built views | No device — M3 |
| Big voice keyboard, easy to press | 132dp mic button | No device — M3 |
| T9 typing, candidates commit, 中/英, 数字/符号 | implemented | Logic reviewed; no device — M3 |
| Online suggestions + offline fallback | implemented; endpoint live-verified | Endpoint yes; in-app — M3 |
| Voice: hold + tap, interim live, final complete once | implemented (M1 protocol port) | **No — no mic/device here** |
| Clipboard last 10, tap paste | implemented | No device — M3 |
| Gboard light colors | palette implemented | Side-by-side — M3 |
| Auto height, open/close | screen-scaled rows, lifecycle resets | No device — M3 |
| Mandarin accuracy | M1: CER 0.00% on TTS | Real mic — M3 |
| No-network/no-permission/failure: message, no crash/freeze | all paths guarded | Code review only — M3 |
| Simple UI, no ads/extra entries | only keyboard/voice/clipboard + disabled v2 label | Yes (static) |

## 3. Dependencies (declared per task_m2b rule)

Only two runtime dependencies, both standard and Maven-resolvable:
- `androidx.core:core-ktx:1.13.1` (permission helpers)
- `com.squareup.okhttp3:okhttp:4.12.0` (online suggestions + ASR WebSocket; brings okio transitively)

Build tooling: AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7 (official wrapper jar). No official Doubao SDK dependency (see Chunk 5 decision). No other third-party code.

## 4. License note (must be resolved before any release packaging)

The committed compact DB is **derived from**: rime-luna-pinyin (**LGPLv3**), rime-ice base (**GPLv3**), and FrequencyWords `zh_full.txt` (OpenSubtitles corpus, **no explicit license** in its README). The frequency file is **build-time only and NOT shipped** in the APK; the derived rankings are baked into the DB, which does ship. For this personal test APK that is acceptable per the brief, but **license compatibility (GPLv3 derivation + unclear freq-list license) must be confirmed before any release/distribution packaging**. Raw dict files are gitignored and not committed.

## 5. What could NOT be verified on this Box (plain statement)

- No Android SDK, no kotlinc, no JVM toolchain, no Android device, no microphone (and none installed, per task_m2c.md). Therefore: **the project has not been compiled here; no APK exists locally; nothing was installed or run on Android.** The Actions build **has not run yet** ("not yet built on Actions").
- Verified here instead: DB conversion + 16/16 regression against the built DB (real run), live Google endpoint check, static delimiter/secret scans, wrapper integrity, gitignore + committed-tree leak scan (DOUBAO appears only as env-var/secret/BuildConfig references; no key value anywhere; no long secret-like literals).
- Remaining risk: Kotlin compile errors would surface in the first Actions run; the code was written conservatively (plain Views, stable APIs, all minSdk-28-safe) and statically reviewed to minimize that risk.

## 6. Next steps (not part of this run)

1. Push main to GitHub (parent decision; this run committed locally only).
2. Optionally set repo secret `DOUBAO_API_KEY_FROM_CONFIG` so the Actions APK has voice configured; without it the APK builds and types normally, voice shows "语音未配置".
3. Read the Actions log / download artifact `voiceime-debug-apk`; fix any compile errors.
4. M3: install on the Honor device, set as default, run the §8 checklist for real (both voice modes, clipboard, Gboard color comparison, speed/feel).
