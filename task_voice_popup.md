# VoiceIME 语音弹窗 + 后端语义修正

## GOAL (only success criterion)
Voice input shows live recognized text in a small popup above the keyboard that grows with the text; tapping 结束 sends the text to the backend for semantic correction and auto-commits the corrected text; the 修改 button exists bottom-right matching the back button; build passes and APK is produced.

## OUTPUT-FIRST RULE
Make your FIRST code edit within ~15 tool calls. Reading without writing is not progress. Do not do broad Grep/Glob sweeps. Only read the files named below (plus the files you must edit). If stuck for more than 15 calls without an edit, stop and report.

## Context (already verified by coordinator, do not re-investigate)
- HEAD e1584be. Voice flow in VoiceImeService.kt: startRecording(hold) -> DoubaoAsrClient callbacks onReady / onInterim(text) / onFinal(text) / onError / onClosed; onInterim -> voiceView.setInterim(text); onFinal -> onAsrFinal(text) -> commitOnce(text) -> commitText once; stopRecording() -> PROCESSING -> sendAudio(last=true) -> waits for final (FINAL_TIMEOUT_MS).
- VoiceKeyboardView.kt: big mic (132dp), statusText, interimText inside a ScrollView with LayoutParams(MATCH_PARENT, 0, 1f) — this interim area collapses/gets cut off, do NOT rely on it for text display anymore. Bottom row: backKey ("返回键盘", 15sp, keyBackground function=true, padding 18dp/10dp) left, spacer weight 1, editReserved ("修改", 13sp, alpha 0.55, disabled) right.
- Backend is LIVE and verified: POST https://toolseverything.com:442/voiceime-correct/correct {"text": "..."} -> {"ok": true, "corrected": "..."} (model deepseek-flash on VPS). GET /health -> {"ok": true}. Do NOT change the backend. App talks to it with OkHttp (already a dependency). No API key in the app for correction — the backend holds the key. URL is a public endpoint, put it as a const string in code.
- Do NOT touch: ASR pipeline (DoubaoAsrClient, AudioCapture, AsrProtocol), lexicon, typing keyboard, clipboard, colors.

## Chunk 1 — Popup + 修改 button (do this first, verify, then Chunk 2)
1. Popup: when recording starts, show a PopupWindow anchored above the keyboard input view (above VoiceKeyboardView, below the app text field). It contains a ScrollView + TextView showing live interim text then final text, and an "结束" button. Height grows with content up to a max (~180dp), then scrolls internally; never covers the text field. Update text on every onInterim; on final show final text.
2. 结束 button: stops recording (same as releasing mic / second tap: call the service stopRecording path), keep popup visible showing "修正中…" while Chunk 2 correction runs.
3. 修改 button: in VoiceKeyboardView bottom row, turn the disabled editReserved into a real button exactly matching backKey size/style (15sp, same background, same padding 18dp/10dp, enabled, full alpha). Add onModifyClick callback to the service. For now the service only shows a short Toast "修改功能稍后上线" — no text changes, no crash, no interference with recording.
4. Keep the old interimText in VoiceKeyboardView but stop using it for display (leave it empty); the popup owns text display now. Do not break panel heights: popup is a separate window, it must not change the 316dp panel height / 361dp total.

## Chunk 2 — Backend correction + commit (only after Chunk 1 is done)
1. New file app/src/main/java/com/voiceime/asr/SemanticCorrector.kt: OkHttp POST JSON {"text": ...} to the URL above, 15s timeout, parse {"ok","corrected"}. Return corrected text, or null on any failure / timeout / empty.
2. Wire into VoiceImeService: onAsrFinal(text) no longer commits directly. Instead: state PROCESSING, popup shows "修正中…", call corrector on a background thread; on success commit corrected text (fallback to original text if corrected is empty); on failure/timeout commit the ORIGINAL text; either way exactly once (reuse commitOnce), then cleanupSession, popup dismisses, status back to idle/done. Interim text is never committed to the input box.
3. If the user switches mode / input finishes while correction is in flight: cancel/ignore the late callback (sessionId guard, same pattern as ASR callbacks) and never commit to a dead connection.
4. Bump versionName 1.1 -> 1.2, versionCode 2 -> 3 in app/build.gradle.kts.

## Verify (each chunk)
- After each chunk: `git diff --stat` and read your own diff once; confirm only intended files changed.
- Do NOT run gradle locally (no Android SDK on this Box). Compilation is verified by GitHub Actions after push.
- Commit each chunk separately with a clear message, push to origin main (PAT is configured in the environment for git push; never print it).

## Rules
- Never print, log, or write any API key / PAT value. The Doubao key stays via BuildConfig secret as before; the correction backend needs no key in the app.
- No broad exploration. No changes outside the files listed: VoiceKeyboardView.kt, VoiceImeService.kt, new SemanticCorrector.kt, app/build.gradle.kts, strings if a new label is needed.
