# VoiceIME Part E (revised): height as rows x (keyHeight + gap), OUR numbers only

## GOAL (only success criterion)
Panel height is computed as rows x (keyHeight + keyGap) + padding, using OUR current numbers; default stays 316dp panel / 361dp total; layout, key shapes, fonts, corner radius, arrangement all UNCHANGED.

## OUTPUT-FIRST RULE
Read the two files named below, then make the edit immediately. Do not plan at length. Do not do broad Grep/Glob sweeps.

## Context (already verified by coordinator, do not re-investigate)
- HEAD is c8a46fa. Working tree is clean. Verify with `git log --oneline -2` first. Do NOT reset.
- Current height (user-confirmed normal): panel 316dp = paddingTop 2dp + paddingBottom 4dp + 5 x keyHeight 62dp; root total 361dp. T9 grid is 5 rows.
- User correction 2026-11-21 15:25: do NOT copy WeChat/Doubao key width, font size, corner radius, or spacing values onto our keyboard; do NOT change our key shapes or arrangement to look like theirs. REA report is reference only. Keep OUR layout; only express the height with the rows x (keyHeight + gap) formula, with OUR numbers.

## Work
1. Read T9KeyboardView.kt (panelHeightPx) and the panelHeight use in VoiceImeService.kt.
2. Change ONLY the height computation: define keyHeight 58dp and keyGap 4dp (pitch 62dp, our current value), panelHeight = paddingTop + paddingBottom + rows x (keyHeight + keyGap). 5 rows -> 2 + 4 + 5 x 62 = 316dp, identical to now. Symbols page (6 rows) uses the same formula. Do NOT touch key width, font sizes, corner radius, spacing of keys, or any layout arrangement.
3. Verify: `git diff --stat`, read your own diff once. Commit with a message recording the numbers. DO NOT push.

## Rules
- Never print, log, or write any API key / PAT value.
- Do NOT run gradle locally (no Android SDK on this Box).
- No changes outside: T9KeyboardView.kt, VoiceImeService.kt.
