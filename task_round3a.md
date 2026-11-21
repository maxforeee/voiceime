# VoiceIME 第三轮 A：后端修正接线（只做这一块）

## GOAL (only success criterion)
Voice final text goes through the backend semantic correction and auto-commits corrected text; on backend failure/timeout the ORIGINAL text is committed; never stuck, never lost text; interim text never enters the input box.

## OUTPUT-FIRST RULE
Make your FIRST code edit within ~15 tool calls. Reading without writing is not progress. Do not do broad Grep/Glob sweeps. Only read the files named below (plus the files you must edit).

## Context (already verified by coordinator, do not re-investigate)
- Working tree ALREADY contains uncommitted Chunk 1 changes (popup + 修改 button) on top of HEAD e1584be. Verify with `git diff --stat` first, keep all of it, do NOT reset. Commit Chunk 1 as its own commit FIRST, then do Part A.
- Popup behavior now: showVoicePopup() on startRecording; onInterim -> setPopupText(text); stopRecording -> setPopupCorrecting(true); onAsrFinal still commits the RAW text directly via commitOnce(text) — that is what you must change. POPUP_LINGER_MS = 1500L is defined.
- Backend is LIVE and verified: POST https://toolseverything.com:442/voiceime-correct/correct {"text": "..."} -> {"ok": true, "corrected": "..."} (model deepseek-flash on VPS). GET /health -> {"ok": true}. Do NOT change the backend. App talks to it with OkHttp (already a dependency). No API key in the app for correction — the backend holds the key. URL is a public endpoint, put it as a const string in code.
- Do NOT touch: ASR pipeline (DoubaoAsrClient, AudioCapture, AsrProtocol), lexicon, typing keyboard, clipboard, colors, popup code except the wiring below.

## Work
1. Commit Chunk 1 first: `git add` only the 3 modified files (VoiceImeService.kt, VoiceKeyboardView.kt, strings.xml), commit message "Voice popup above keyboard + modify button placeholder".
2. New file app/src/main/java/com/voiceime/asr/SemanticCorrector.kt: OkHttp POST JSON {"text": ...} to the URL above, 15s timeout, parse {"ok","corrected"}. Return corrected text, or null on any failure / timeout / empty.
3. Wire into VoiceImeService: onAsrFinal(text) no longer commits directly. Instead: state PROCESSING, popup shows "修正中…" (setPopupCorrecting(true) is already called in stopRecording), call corrector on a background thread; on success commit corrected text (fallback to original text if corrected is empty); on failure/timeout commit the ORIGINAL text; either way exactly once (reuse commitOnce), then cleanupSession, popup dismisses after the usual linger, status back to idle/done. Interim text is never committed to the input box.
4. If the user switches mode / input finishes while correction is in flight: cancel/ignore the late callback (sessionId guard, same pattern as ASR callbacks) and never commit to a dead connection.
5. Verify: `git diff --stat`, read your own diff once, confirm only intended files changed. Commit Part A separately. DO NOT push (the next round pushes).

## Rules
- Never print, log, or write any API key / PAT value. The Doubao key stays via BuildConfig secret as before; the correction backend needs no key in the app.
- Do NOT run gradle locally (no Android SDK on this Box). Compilation is verified by GitHub Actions after push.
- No broad exploration. No changes outside: VoiceImeService.kt, new SemanticCorrector.kt.
