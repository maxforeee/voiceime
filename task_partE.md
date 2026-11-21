# VoiceIME Part E only: height by formula, vendor numbers

## GOAL (only success criterion)
Keyboard height is computed by rows x (key height + gap) with vendor-derived numbers; default stays 316dp panel / 361dp total.

## OUTPUT-FIRST RULE
Make your FIRST code edit within ~15 tool calls. Reading without writing is not progress. Only read the files named below (plus the files you must edit).

## Context (already verified by coordinator, do not re-investigate)
- HEAD is c8a46fa (popup + correction committed). Verify with `git log --oneline -3` first. Working tree is clean. Do NOT reset.
- Current height (user-confirmed normal): panel 316dp = paddingTop 2dp + paddingBottom 4dp + 5 x keyHeight 62dp; root total 361dp (44dp top bar + 1dp divider + 316dp panel). T9 grid is 5 rows (4 key rows + bottom row). Default height after your change must be the same 316dp/361dp (never taller at default).
- REA numbers (algorithm + numbers only, NO vendor code, NO vendor packages):
  - Doubao: key height 44 + gap 8 = 52 per row; total height = rows x (key height + gap) — computed, never measured.
  - WeChat S1 9-key (1080px width baseline, 1080px = 360dp so divide px by 3): total 628px = ~209dp, 4 rows, row height 140px = ~47dp, row spacing [10,8,8,8,10]px, key width 210px = 70dp, key height 139px = ~46dp, main font 50-56px = ~17-19sp, digit sub-label 26px = ~9sp top-right, corner radius 16px = ~5dp.

## Work
1. Read T9KeyboardView.kt (panelHeightPx) and VoiceImeService.kt (panelHeight use) first.
2. Replace the hardcoded height with the formula: panelHeight = paddingTop + paddingBottom + rows x (keyHeight + keyGap). Pick keyHeight/keyGap so the 5-row T9 panel stays 316dp at default (e.g. keyHeight 58 + gap 4 = 62 pitch -> 310 + 6 = 316, identical to now). Adopt vendor numbers where they fit: key corner radius ~5dp, main key font ~18sp, digit sub-label ~9sp top-right, key gap 4-8dp. Record the exact numbers you chose in the commit message.
3. Symbols page (6 rows) and voice panel must keep working with the same formula (rows x pitch + padding).
4. Verify: `git diff --stat`, read your own diff once, confirm only intended files changed. Commit. DO NOT push.

## Rules
- Never print, log, or write any API key / PAT value.
- Do NOT run gradle locally (no Android SDK on this Box).
- No broad exploration. No changes outside: T9KeyboardView.kt, VoiceImeService.kt.
