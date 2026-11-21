# GOAL (only success criterion)
Fix the 4 Kotlin compile errors from GitHub Actions run 44864538962 so ./gradlew assembleDebug compiles. Do NOT change any feature or behaviour.

# OUTPUT-FIRST RULE
Make your FIRST code Edit within 15 tool calls. Reading without writing is not progress. Long silent thinking with no visible action is failure. After the edits, run git diff to show exactly what changed.

# The 4 errors (verified from the build log /tmp/runlogs/build/5_Build debug APK.txt)
1. VoiceImeService.kt:543:33 - currentInputConnection?.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) - Unresolved reference. InputConnection has no sendDownUpKeyEvents. The method sendDownUpKeyEvents(int) exists on InputMethodService itself. Fix: call it on the service (drop the currentInputConnection?. receiver), same behaviour.
2. VoiceImeService.kt:568:16 - ic.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) - same fix: call sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) on the service.
3. GoogleSuggestions.kt:69:57 - Closing bracket expected. Line 69 is a KDoc comment whose text contains double square brackets; KDoc parses double brackets as a link, and the nested brackets break it. Fix: rewrite that comment in plain words without any double brackets, for example: Response shape: SUCCESS with groups of (input, candidates). Do NOT change the parse() code.
4. UiUtils.kt:15:31 - cornerRadius = ctx.dp(6).toFloat() - dp(v: Float) is called with Int literal 6. Fix: ctx.dp(6f).toFloat(). This is the only Int-literal dp() call in the codebase (all others already use the f suffix) - do not touch other call sites.

# Rules
- Only edit these 3 files: app/src/main/java/com/voiceime/VoiceImeService.kt, app/src/main/java/com/voiceime/online/GoogleSuggestions.kt, app/src/main/java/com/voiceime/ui/UiUtils.kt. You may also read /tmp/runlogs/build/5_Build debug APK.txt for the log lines. Nothing else.
- No broad Grep/Glob sweeps. No new features, no refactoring, no formatting changes.
- Do NOT commit or push. Only edit files and report the diff. The coordinator commits and pushes.
- Do NOT print or use any API key. No key is needed for this fix.
