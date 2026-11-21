# VoiceIME 英文 26 键 QWERTY 全键盘

## GOAL (only success criterion)
English input uses a full 26-key QWERTY keyboard; Chinese stays 9-key T9; language switch and digits/symbols page unchanged; build passes.

## OUTPUT-FIRST RULE
Make your FIRST code edit within ~15 tool calls. Reading without writing is not progress. Do not do broad Grep/Glob sweeps. Only read the files named below (plus the files you must edit). If stuck for more than 15 calls without an edit, stop and report.

## Context (already verified by coordinator, do not re-investigate)
- HEAD may have moved: the voice popup + backend correction round ran just before you. `git pull` first; do NOT reset, do NOT touch its changes.
- Current English input is multitap on the 9-key grid (VoiceImeService.kt: Lang EN, onEnglishKey / finalizeTap / tapKey / tapIndex; T9KeyboardView showMain / showSymbols). Chinese 9-key pinyin + segmentation + candidates must NOT change.
- Fixed panel height contract: 316dp panel / 361dp total. QWERTY rows must fit the SAME panel height — 4 rows (3 letter rows + bottom row) within the same height; adjust key height if needed, do not change panelHeightPx.
- Keep untouched: top bar (candidate bar + clipboard + toggle), voice popup + correction flow, bottom nav color, clipboard.

## Work
1. Read T9KeyboardView.kt and the English path in VoiceImeService.kt first (only these two files + strings if a new label is needed).
2. Add a QWERTY keyboard view (extend T9KeyboardView with a QWERTY mode, or a new QwertyKeyboardView.kt — whichever is smaller): 3 letter rows (qwertyuiop / asdfghjkl / zxcvbnm), bottom row with shift, wide space, delete, enter, and language/symbol switching consistent with the current UI. Keys show lowercase letters; shift toggles case for one tap (caps lock on double-tap only if trivial, otherwise single-tap shift). No multitap, no per-key letter cycling.
3. Wire: lang == EN shows QWERTY; ZH shows 9-key. Language switch button behavior unchanged. Digits/symbols page unchanged (still reachable the same way). Delete/space/enter reuse the existing handlers where possible (onDelete / onSpace / onEnter / commit).
4. English typing commits letters directly. Candidate bar behavior in EN mode: keep whatever it does now (empty/hidden) — do not add English suggestions.
5. Reuse the existing key popup preview if it is shared code; otherwise plain press feedback is fine.
6. Pinyin segmentation dropdown (replaces v1.1 tap-to-cycle): the pinyin label in CandidateBar keeps a ▾ triangle next to it; tapping the triangle opens a dropdown list below it — one segmentation per row, all segmentations from lastLocal.segmentations; tapping a row selects that segmentation, applies it (same effect as the old cycleSegmentation but to the chosen index), and the choice sticks — no cycling. Tapping outside dismisses the list without changing anything. Reuse the existing segmentation data (CandidateResult.segmentations) and refreshCandidates path; do not change lookup ranking.
7. Delete key long-press deletes the current sentence: tap still deletes one char (existing onDelete, unchanged). Long-press on delete deletes from the cursor back to just after the previous sentence-ending period — Chinese "。" or English "." — i.e. the whole current sentence, NOT everything. If there is no 。/. before the cursor, delete everything before the cursor. Apply in both the 9-key and the new QWERTY layouts (the delete key exists in both). Read the current onDelete and the keyboard key-press path first; reuse getTextBeforeCursor / deleteSurroundingText; while a pinyin composition is uncommitted, long-press behaves like the existing delete path for that state (do not delete committed text by accident).
8. Version bump: check app/build.gradle.kts first. If the popup round already bumped to 1.2/3, bump to 1.3/4. If it is still 1.1/2, bump to 1.2/3.
7. Verify: `git diff --stat`, read your own diff once, confirm only intended files changed.

## Verify
- Do NOT run gradle locally (no Android SDK on this Box). Compilation is verified by GitHub Actions after push.
- Commit with a clear message, push to origin main (PAT is configured in the environment for git push; never print it).

## Rules
- Never print, log, or write any API key / PAT value.
- No broad exploration. No changes outside: T9KeyboardView.kt (or new QwertyKeyboardView.kt), VoiceImeService.kt, app/build.gradle.kts, strings if a new label is needed.
