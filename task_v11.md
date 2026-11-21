# VoiceIME v1.1 — task_v11.md

## GOAL (the only success criterion)
Fix the 4 user-reported v1 issues in the VoiceIME Android app (repo /workspace/voiceime, branch main @ 29932b3) and leave the working tree compiling cleanly, so the next push builds a v1.1 APK on GitHub Actions. Do NOT touch the voice-recognition chain (asr/, DoubaoAsrClient, GoogleSuggestions). Do NOT add features.

## OUTPUT-FIRST RULE (read this first)
You must make your FIRST code Edit within ~15 tool calls of starting each chunk. Reading without writing is not progress. Long silent thinking with no visible action is failure. If you are unsure of an API, make the most reasonable edit, build, and fix from the compiler error — do not keep exploring. Work chunk by chunk: finish chunk N, verify it compiles, then move to chunk N+1. Never run broad Grep/Glob sweeps; each chunk lists the ONLY files you may read (plus this task file). Do not read other files.

## Context (already diagnosed — do NOT re-investigate)
- v1 feedback, root causes known: (1) pinyin label shows enumeration-first segmentation, not the one producing the top word; (2) bottom row 3 equal keys, tiny sub-labels, 3dp gaps, no popup/haptic; (3) all three panels WRAP_CONTENT, clipboard list height 0 + weight 1 collapses to 0; (4) navigation bar never styled, breaks against #ECEFF1 keyboard.
- Keyboard bg color already exists: R.color.keyboard_bg = #ECEFF1 in app/src/main/res/values/colors.xml.
- Base package: com.voiceime. App module: app/build.gradle.kts, versionCode = 1, versionName = "0.1.0".
- After EVERY chunk: run `./gradlew :app:compileDebugKotlin` (use /home/user/flutter/bin/dart? NO — this is Android: use ./gradlew; if gradlew is slow, at minimum run it once per chunk). Report PASS/FAIL per chunk at the end of your reply. Do NOT commit or push — the coordinator commits.

## Chunk 1 — Pinyin segmentation choice
ONLY files: app/src/main/java/com/voiceime/lexicon/Lexicon.kt, app/src/main/java/com/voiceime/Candidate.kt, app/src/main/java/com/voiceime/ui/CandidateBar.kt, app/src/main/java/com/voiceime/VoiceImeService.kt
1. Lexicon.lookup() currently returns only segs.firstOrNull() — one segmentation in enumeration order, NOT ranked by word frequency (e.g. input 64426 shows "m g g a m"). Change lookup() to return a LIST of candidate segmentations, ranked by the score of the best word each segmentation produces.
2. CandidateResult gains a `segmentations` field carrying that ranked list.
3. The pinyin label in the candidate bar becomes tappable: tap cycles to the next segmentation (or shows a small segmentation list to pick from) and re-queries candidates for the chosen segmentation.
4. The label by default shows the segmentation that produced the top-ranked (first-choice) word — never the enumeration-first item.

## Chunk 2 — Typing feel
ONLY files: app/src/main/java/com/voiceime/ui/T9KeyboardView.kt, app/src/main/java/com/voiceime/VoiceImeService.kt
1. Bottom row (symbols / space / enter) is 3 equal-width keys: change weights so space takes 2 parts and each side key 1 part.
2. Letter sub-labels: grow from ~0.5x to ~0.65x of key text size and raise contrast so they are clearly readable.
3. Increase key spacing from 3dp to a slightly larger value (4–5dp).
4. Add key-press popup preview (the pressed key pops up enlarged above the finger) and light haptic feedback on key press.
5. Key height cap is 58dp: pick a sensible value for a 6.8-inch large screen (raise modestly, e.g. 60–64dp) — do not exceed what fits the fixed total height in chunk 3.
6. In VoiceImeService.kt, slim the top bar: the fixed 44dp clipboard icon + 48dp switch icon are too wide; shrink them to free width for the candidate bar.

## Chunk 3 — Uniform panel heights (Gboard fixed-height approach)
ONLY files: app/src/main/java/com/voiceime/VoiceImeService.kt, app/src/main/java/com/voiceime/ui/ClipboardPanelView.kt, app/src/main/java/com/voiceime/ui/VoiceKeyboardView.kt
1. In onCreateInputView(), compute the total height ONCE, keyed to the typing keyboard (~341dp), and set the SAME fixed height in LayoutParams for all three panels: typing, voice, clipboard. Total height must NOT change when switching panels.
2. Panels scroll internally instead of growing.
3. Clipboard list must NOT use height 0 + weight 1 inside a wrap-content chain (that collapses to 0, leaving only the title bar): give the list a fixed height or MATCH_PARENT within the fixed-height panel.
4. Voice panel intermediate-result scroll area: same treatment (fixed height / MATCH_PARENT inside the fixed panel).
5. Symbol page: either merge it back into 5 rows, or shrink key height within the same total height — total height must stay identical when switching to it.

## Chunk 4 — Bottom colour + version bump
ONLY files: app/src/main/java/com/voiceime/VoiceImeService.kt, app/build.gradle.kts
1. In onCreateInputView(), add `window?.navigationBarColor = getColor(R.color.keyboard_bg)` and, for API 29+, `window?.isNavigationBarContrastEnforced = false` (targetSdk 34, minSdk 28 both support this).
2. Add a bottom filler view in the keyboard background colour at the bottom of the keyboard, so the colour still covers the area if the window is taller than the content.
3. Version bump in app/build.gradle.kts: versionName "0.1.0" -> "1.1", versionCode 1 -> 2.

## Final verification (after chunk 4)
Run `./gradlew :app:compileDebugKotlin` once more and report the result. Then print `git diff --stat` and `git status --short`. Do NOT commit, do NOT push, do NOT touch any other repo.
