# VoiceIME 第二轮：后端修正接线 + 英文 QWERTY + 拼音下拉 + 删除长按

## GOAL (only success criterion)
Voice final text goes through the backend semantic correction and auto-commits corrected text (raw text on failure); English uses a full 26-key QWERTY keyboard; pinyin segmentation is chosen from a dropdown list; long-press delete removes the whole current sentence; build passes and APK is produced.

## OUTPUT-FIRST RULE
Make your FIRST code edit within ~15 tool calls. Reading without writing is not progress. Do not do broad Grep/Glob sweeps. Only read the files named below (plus the files you must edit). If stuck for more than 15 calls without an edit, stop and report. Work in order A → B → C → D; commit after each part; push only once at the very end.

## Context (already verified by coordinator, do not re-investigate)
- Working tree ALREADY contains uncommitted Chunk 1 changes (popup + 修改 button) on top of HEAD e1584be: VoiceImeService.kt now has showVoicePopup / setPopupText / setPopupCorrecting / dismissVoicePopup, POPUP_LINGER_MS = 1500L; VoiceKeyboardView.kt has the enabled 修改 button with onModifyClick (Toast placeholder in the service); strings.xml has voice_popup_end / voice_correcting / voice_edit / voice_edit_soon. Verify with `git diff` first, keep all of it, do NOT reset. Commit Chunk 1 as its own commit first.
- Popup behavior now: showVoicePopup() on startRecording; onInterim -> setPopupText(text); stopRecording -> setPopupCorrecting(true); onAsrFinal still commits the RAW text directly via commitOnce(text) — that is what Part A must change.
- Backend is LIVE and verified: POST https://toolseverything.com:442/voiceime-correct/correct {"text": "..."} -> {"ok": true, "corrected": "..."} (model deepseek-flash on VPS). GET /health -> {"ok": true}. Do NOT change the backend. App talks to it with OkHttp (already a dependency). No API key in the app for correction — the backend holds the key. URL is a public endpoint, put it as a const string in code.
- Current English input is multitap on the 9-key grid (VoiceImeService.kt: Lang EN, onEnglishKey / finalizeTap / tapKey / tapIndex; T9KeyboardView showMain / showSymbols). Chinese 9-key pinyin + segmentation + candidates must NOT change.
- Pinyin segmentation now: v1.1 tap on the pinyin label cycles segmentations (CandidateBar onSegmentationTap -> cycleSegmentation()). CandidateResult.segmentations holds all options.
- Fixed panel height contract: 316dp panel / 361dp total — do not change panelHeightPx.
- Do NOT touch: ASR pipeline (DoubaoAsrClient, AudioCapture, AsrProtocol), lexicon, typing keyboard Chinese path, clipboard, colors, voice popup code except the wiring Part A needs.

## Part A — Backend correction + commit (do first)
1. New file app/src/main/java/com/voiceime/asr/SemanticCorrector.kt: OkHttp POST JSON {"text": ...} to the URL above, 15s timeout, parse {"ok","corrected"}. Return corrected text, or null on any failure / timeout / empty.
2. Wire into VoiceImeService: onAsrFinal(text) no longer commits directly. Instead: state PROCESSING, popup shows "修正中…" (setPopupCorrecting(true) is already called in stopRecording), call corrector on a background thread; on success commit corrected text (fallback to original text if corrected is empty); on failure/timeout commit the ORIGINAL text; either way exactly once (reuse commitOnce), then cleanupSession, popup dismisses after the usual linger, status back to idle/done. Interim text is never committed to the input box.
3. If the user switches mode / input finishes while correction is in flight: cancel/ignore the late callback (sessionId guard, same pattern as ASR callbacks) and never commit to a dead connection.
4. Commit Part A separately.

## Part B — English 26-key QWERTY (Chinese stays 9-key)
1. Read T9KeyboardView.kt and the English path in VoiceImeService.kt first.
2. Add a QWERTY keyboard view (extend T9KeyboardView with a QWERTY mode, or a new QwertyKeyboardView.kt — whichever is smaller): 3 letter rows (qwertyuiop / asdfghjkl / zxcvbnm), bottom row with shift, wide space, delete, enter, and language/symbol switching consistent with the current UI. Keys show lowercase letters; shift toggles case for one tap (caps lock on double-tap only if trivial, otherwise single-tap shift). No multitap, no per-key letter cycling. Rows must fit the SAME 316dp panel height (4 rows total incl. bottom row); adjust key height if needed, do not change panelHeightPx.
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
- Bump versionName 1.1 -> 1.2, versionCode 2 -> 3 in app/build.gradle.kts (check the file first; it should still be 1.1/2 since the popup round did not bump it).
- Verify: `git diff --stat` per part, read your own diff once per part, confirm only intended files changed.
- Do NOT run gradle locally (no Android SDK on this Box). Compilation is verified by GitHub Actions after push.
- Push to origin main ONCE at the very end (PAT is configured in the environment for git push; never print it).

## Rules
- Never print, log, or write any API key / PAT value. The Doubao key stays via BuildConfig secret as before; the correction backend needs no key in the app.
- No broad exploration. No changes outside: VoiceImeService.kt, VoiceKeyboardView.kt, new SemanticCorrector.kt, T9KeyboardView.kt (or new QwertyKeyboardView.kt), CandidateBar.kt, app/build.gradle.kts, strings if a new label is needed.
