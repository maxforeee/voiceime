# VoiceIME 第三轮 B2：英文 QWERTY + 拼音下拉 + 删除长按（最后构建推送）

## GOAL (only success criterion)
English uses a full 26-key QWERTY keyboard; pinyin segmentation is chosen from a dropdown list; long-press delete removes the whole current sentence; version bumped; build passes and APK is produced.

## OUTPUT-FIRST RULE
Make your FIRST code edit within ~15 tool calls. Reading without writing is not progress. Do not do broad Grep/Glob sweeps. Only read the files named below (plus the files you must edit). If stuck for more than 15 calls without an edit, stop and report. Work in order H -> B -> C -> D; commit after each part; push only once at the very end.

## Context (already verified by coordinator, do not re-investigate)
- Rounds 3a and 3b1 (just before you) committed: popup + 修改 button, SemanticCorrector + wiring, height formula (panelHeight = paddingTop + paddingBottom + rows x (keyHeight + keyGap), default 316dp panel / 361dp total), settings gear panel with keyboard-height tool and offline voice pack. Verify with `git log --oneline -8` first — their commits must be there. Do NOT reset, do NOT touch their changes.
- Correction-backend auth (done on the VPS, verified): POST /correct now requires header `X-Correct-Token` matching the shared secret stored in /opt/voiceime-correct/.env; missing/wrong token -> HTTP 401 {"ok": false, "error": "unauthorized"}. The secret is registered as GitHub repo secret VOICEIME_CORRECT_TOKEN (created by the coordinator via browser, same as DOUBAO_API_KEY_FROM_CONFIG). SemanticCorrector must send it; 401/403 must fall back to the original text (the existing null-on-non-2xx path already does this — keep it).
- Current English input is multitap on the 9-key grid (VoiceImeService.kt: Lang EN, onEnglishKey / finalizeTap / tapKey / tapIndex; T9KeyboardView showMain / showSymbols). Chinese 9-key pinyin + segmentation + candidates must NOT change.
- Pinyin segmentation now: v1.1 tap on the pinyin label cycles segmentations (CandidateBar onSegmentationTap -> cycleSegmentation()). CandidateResult.segmentations holds all options.
- Do NOT touch: ASR pipeline (DoubaoAsrClient, AudioCapture, AsrProtocol), lexicon, voice popup + correction flow, offline ASR, settings panel, clipboard, colors except where a part needs it.

## Part H — Correction token in the app (do first)
1. Read app/build.gradle.kts, .github/workflows/android.yml, and SemanticCorrector.kt first. Wire the shared secret through exactly like the Doubao key: workflow reads secrets.VOICEIME_CORRECT_TOKEN into a BuildConfig field (e.g. CORRECT_TOKEN, empty string when the secret is absent — the build must NOT fail if the secret is missing), and SemanticCorrector sends it as request header `X-Correct-Token` on every /correct call.
2. 401/403 from the backend must keep the existing fallback: correct() returns null, the caller commits the ORIGINAL text. Do not change that path; do not retry on 401.
3. Never print, log, or hardcode the token value anywhere. Commit Part H separately.

## Part B — English 26-key QWERTY (Chinese stays 9-key)
1. Read T9KeyboardView.kt and the English path in VoiceImeService.kt first.
2. Add a QWERTY keyboard view (extend T9KeyboardView with a QWERTY mode, or a new QwertyKeyboardView.kt — whichever is smaller): 3 letter rows (qwertyuiop / asdfghjkl / zxcvbnm), bottom row with shift, wide space, delete, enter, and language/symbol switching consistent with the current UI. Keys show lowercase letters; shift toggles case for one tap (caps lock on double-tap only if trivial, otherwise single-tap shift). No multitap, no per-key letter cycling. Rows must fit the SAME panel height via the Part E formula (4 rows total incl. bottom row); adjust row count in the formula, do not change the formula itself.
3. Wire: lang == EN shows QWERTY; ZH shows 9-key. Language switch button behavior unchanged. Digits/symbols page unchanged. Delete/space/enter reuse the existing handlers where possible (onDelete / onSpace / onEnter / commit).
4. English typing commits letters directly. Candidate bar behavior in EN mode: keep whatever it does now — do not add English suggestions.
5. Reuse the existing key popup preview if it is shared code; otherwise plain press feedback is fine.
6. Commit Part B separately.

## Part C — Pinyin segmentation dropdown (replaces tap-to-cycle)
1. The pinyin label in CandidateBar keeps a ▾ triangle next to it; tapping the triangle opens a dropdown list below it — one segmentation per row, all segmentations from lastLocal.segmentations; tapping a row selects that segmentation, applies it (same effect as the old cycleSegmentation but to the chosen index), and the choice sticks — no cycling. Tapping outside dismisses the list without changing anything.
2. Reuse the existing segmentation data (CandidateResult.segmentations) and refreshCandidates path; do not change lookup ranking. The old tap-to-cycle on the label itself goes away (tap on the label text does nothing; only the ▾ opens the list).
3. Commit Part C separately.

## Part D — Delete key long-press deletes the current sentence
1. Tap still deletes one char (existing onDelete, unchanged). Long-press on delete deletes from the cursor back to just after the previous sentence-ending period — Chinese "。" or English "." — i.e. the whole current sentence, NOT everything. If there is no 。/. before the cursor, delete everything before the cursor.
2. Apply in both the 9-key and the new QWERTY layouts (the delete key exists in both). Read the current onDelete and the keyboard key-press path first; reuse getTextBeforeCursor / deleteSurroundingText; while a pinyin composition is uncommitted, long-press behaves like the existing delete path for that state (do not delete committed text by accident).
3. Commit Part D separately.

## Finish
- Bump versionName 1.1 -> 1.2, versionCode 2 -> 3 in app/build.gradle.kts (check the file first; it should still be 1.1/2 since earlier rounds did not bump it).
- Verify: `git diff --stat` per part, read your own diff once per part, confirm only intended files changed. State the default height numbers from the formula in your final message.
- Do NOT run gradle locally (no Android SDK on this Box). Compilation is verified by GitHub Actions after push.
- Push to origin main ONCE at the very end (PAT is configured in the environment for git push; never print it).

## Rules
- Never print, log, or write any API key / PAT value. The Doubao key stays via BuildConfig secret as before; the correction backend needs no key in the app.
- No broad exploration. No changes outside: T9KeyboardView.kt (or new QwertyKeyboardView.kt), VoiceImeService.kt, CandidateBar.kt, app/build.gradle.kts, strings if a new label is needed.
