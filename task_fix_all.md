# GOAL (only success criterion)
Apply the review fix list below to /workspace/voiceime so the next GitHub Actions build compiles. Do NOT change any feature or behaviour, and do NOT fix anything outside this list.

# OUTPUT-FIRST RULE
Make your FIRST code Edit within 15 tool calls. Reading without writing is not progress. Long silent thinking with no visible action is failure. After the edits, run git diff to show exactly what changed.

# Context
- A previous Qwen round already fixed 4 Kotlin compile errors in the working tree (uncommitted). They are correct; verify them by reading git diff, do not redo them unless you find one wrong:
  1. VoiceImeService.kt:543 sendDownUpKeyEvents called on the service, not on InputConnection.
  2. VoiceImeService.kt:568 same.
  3. GoogleSuggestions.kt:69 KDoc comment rewritten without double brackets.
  4. UiUtils.kt:15 ctx.dp(6f).
- Then ocr (open-code-review, DeepSeek) scanned the whole repo: 27 files, 59 comments. Its compile-related claims were checked against the real Gradle build log and are mostly wrong - do NOT act on them: the singular overridesImplicitlyEnabledSubtype and isAuxiliary on subtype are accepted by AAPT (build run 44864538962 passed processDebugResources), plugins resolve fine, and the BuildConfig key is a UUID with no escapable characters.

# Fix list (only these)
1. method.xml: add android:imeSubtypeLocale="zh_CN" to the subtype element (it currently has label, imeSubtypeMode=keyboard, overridesImplicitlyEnabledSubtype=true, isAuxiliary=false). The subtype has no locale, so it may not register in the system IME switcher. One line only. Do NOT remove or move any other attribute.
2. Nothing else. Do NOT fix any other ocr finding (thread races, leaks, state machine, Lexicon bounds, etc.) - those are runtime robustness items for a later round, explicitly out of scope.

# Rules
- Only edit app/src/main/res/xml/method.xml. You may read git diff and the 3 already-fixed Kotlin files to verify them. Nothing else.
- No broad Grep/Glob sweeps. No new features, no refactoring, no formatting changes.
- Do NOT commit or push. Only edit the file and report the diff. The coordinator commits and pushes.
- Do NOT print or use any API key. No key is needed for this fix.
