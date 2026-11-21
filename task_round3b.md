# VoiceIME 第三轮 B：高度算法 + 设置面板 + 英文 QWERTY + 拼音下拉 + 删除长按

## GOAL (only success criterion)
Keyboard height is computed by rows x (key height + gap) with vendor-derived numbers and stays ~316dp panel / 361dp total (or better); top bar has a gear settings button opening a tools panel with a working keyboard-height control (immediate effect, persisted); English uses a full 26-key QWERTY keyboard; pinyin segmentation is chosen from a dropdown list; long-press delete removes the whole current sentence; build passes and APK is produced.

## OUTPUT-FIRST RULE
Make your FIRST code edit within ~15 tool calls. Reading without writing is not progress. Do not do broad Grep/Glob sweeps. Only read the files named below (plus the files you must edit). If stuck for more than 15 calls without an edit, stop and report. Work in order E -> F -> B -> C -> D; commit after each part; push only once at the very end.

## Context (already verified by coordinator, do not re-investigate)
- Round 3a (just before you) committed: Chunk 1 popup + 修改 button, and Part A SemanticCorrector + wiring. Verify with `git log --oneline -4` first — its commits must be there. Do NOT reset, do NOT touch its changes.
- Current height (user-confirmed normal): panel 316dp = paddingTop 2dp + paddingBottom 4dp + 5 x keyHeight 62dp; root total 361dp (44dp top bar + 1dp divider + 316dp panel). T9 grid is 5 rows (4 key rows + bottom row). Do NOT break this: after your change the default height must be the same 316dp/361dp or better (never taller than now at default).
- REA numbers (from REA_COMPARISON.md section 3; algorithm + numbers only, NO vendor code, NO vendor packages):
  - Doubao: key height 44 + gap 8 = 52 per row; total height = rows x (key height + gap) — computed, never measured; 9-key 3-column structure; left column holds the pinyin segmentation list.
  - WeChat S1 9-key (1080px width baseline, 1080px = 360dp so divide px by 3): total 628px = ~209dp, 4 rows, row height 140px = ~47dp, row spacing [10,8,8,8,10]px, key width 210px = 70dp, key height 139px = ~46dp, main font 50-56px = ~17-19sp, digit sub-label 26px = ~9sp top-right, corner radius 16px = ~5dp.
- Current English input is multitap on the 9-key grid (VoiceImeService.kt: Lang EN, onEnglishKey / finalizeTap / tapKey / tapIndex; T9KeyboardView showMain / showSymbols). Chinese 9-key pinyin + segmentation + candidates must NOT change.
- Pinyin segmentation now: v1.1 tap on the pinyin label cycles segmentations (CandidateBar onSegmentationTap -> cycleSegmentation()). CandidateResult.segmentations holds all options.
- Top bar now: candidate bar + clipboard icon + keyboard/voice toggle at the far RIGHT. Clipboard panel opens from the top bar and swaps in place of the keyboard panel (same height/logic) — read how it is shown/hidden in VoiceImeService.kt first.
- Do NOT touch: ASR pipeline (DoubaoAsrClient, AudioCapture, AsrProtocol), lexicon, voice popup + correction flow, clipboard, colors except where a part needs it.

## Part E — Height by formula, vendor numbers (do first)
1. Read T9KeyboardView.kt (panelHeightPx) and VoiceImeService.kt (panelHeight use) first.
2. Replace the hardcoded height with the formula: panelHeight = paddingTop + paddingBottom + rows x (keyHeight + keyGap). Pick keyHeight/keyGap so the 5-row T9 panel stays 316dp at default (e.g. keyHeight 58 + gap 4 = 62 pitch -> 310 + 6 = 316, identical to now). Adopt vendor numbers where they fit: key corner radius ~5dp, main key font ~18sp, digit sub-label ~9sp top-right, key gap 4-8dp. Record the exact numbers you chose in the commit message.
3. Symbols page (6 rows) and voice panel must keep working with the same formula (rows x pitch + padding).
4. Commit Part E separately.

## Part F — Settings gear + keyboard height tool
1. Read the top-bar code in VoiceImeService.kt first. Add a gear settings button at the FAR LEFT of the top bar (clipboard + toggle stay far right). Render it the same way the existing top-bar icons are rendered; if a drawable is needed, add res/drawable/ic_settings.xml (a simple gear vector, drawn by you — no vendor assets).
2. Tap gear -> show a settings panel exactly like the clipboard panel (same container, same height, same show/hide logic), but its content is a tools list. Tapping gear again, or switching to keyboard/clipboard, closes it — same as clipboard behavior.
3. First tool only: "键盘高度" with a SeekBar (or +/- buttons). Changing it updates the panel height IMMEDIATELY (recompute via Part E's formula and re-layout the current panel). It adjusts keyHeight (range about 52-72dp, default = the Part E value); panel height follows the formula. Persist the chosen keyHeight in SharedPreferences; on next keyboard open, use the stored value.
4. Do not add any other tool. Settings panel must not disturb voice popup / correction.
5. Commit Part F separately.

## Part B — English 26-key QWERTY (Chinese stays 9-key)
1. Read T9KeyboardView.kt and the English path in VoiceImeService.kt first.
2. Add a QWERTY keyboard view (extend T9KeyboardView with a QWERTY mode, or a new QwertyKeyboardView.kt — whichever is smaller): 3 letter rows (qwertyuiop / asdfghjkl / zxcvbnm), bottom row with shift, wide space, delete, enter, and language/symbol switching consistent with the current UI. Keys show lowercase letters; shift toggles case for one tap (caps lock on double-tap only if trivial, otherwise single-tap shift). No multitap, no per-key letter cycling. Rows must fit the SAME panel height via Part E's formula (4 rows total incl. bottom row); adjust row count in the formula, do not change the formula itself.
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
- Verify: `git diff --stat` per part, read your own diff once per part, confirm only intended files changed. After the build, verify the default height is still 316dp panel / 361dp total (state the numbers from the formula in your final message).
- Do NOT run gradle locally (no Android SDK on this Box). Compilation is verified by GitHub Actions after push.
- Push to origin main ONCE at the very end (PAT is configured in the environment for git push; never print it).

## Rules
- Never print, log, or write any API key / PAT value. The Doubao key stays via BuildConfig secret as before; the correction backend needs no key in the app.
- No broad exploration. No changes outside: T9KeyboardView.kt (or new QwertyKeyboardView.kt), VoiceImeService.kt, CandidateBar.kt, new SettingsPanel view if needed, res/drawable/ic_settings.xml, app/build.gradle.kts, strings if a new label is needed.
