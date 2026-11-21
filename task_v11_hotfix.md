# VoiceIME v1.1 hotfix — keyboard height explosion

## ONE GOAL
Fix the v1.1 keyboard height bug so the IME window is ~361dp tall (top bar 44dp + divider 1dp + panel ~316dp) and sits at the BOTTOM of the screen, with all three panels (typing / voice / clipboard) the same fixed height. Nothing else changes.

## OUTPUT-FIRST RULE
Make your FIRST code Edit within ~15 tool calls. Reading files without writing is not progress. Long silent thinking with no visible action is failure. Work in this order: read the 4 files listed below, make the edits, run `git diff` to verify, then stop and report. Do NOT commit or push; the coordinator does that.

## CONTEXT (already diagnosed — verify it in the code before changing anything)
- User report (real device, 6.8" screen): after v1.1 the keyboard is sky-high, rendered at the TOP of the screen instead of the bottom.
- v1.1 (commit f614aaf) changed height handling in `VoiceImeService.onCreateInputView()`: it measures the keyboard once with an UNSPECIFIED height spec and hard-codes `panelHeight` into all three panels, AND it added a `bottomFiller` view with `LayoutParams(MATCH_PARENT, 0, 1f)` (weight 1) at the bottom of the root LinearLayout.
- Root cause: when the IME window measures the root view with AT_MOST (screen height), a weight-1 child absorbs ALL remaining space, so the root measures full-screen tall and the keyboard (laid out top-down) ends up at the top of the screen. The `bottomFiller` is the bug. The navigation bar colour is already handled by `window.navigationBarColor = keyboard_bg`, so the filler is redundant.
- Numbers to check against (6.8" screen, keyHeight clamped to 62dp): main page = 5 rows x 62dp + 2dp top + 4dp bottom padding = 316dp; symbol page = 6 rows x symbolKeyHeight (keyHeight*5/6 = 51dp) + 6dp padding = 312dp; total root height should be 44 + 1 + 316 = 361dp.

## FILES YOU MAY READ (only these + build files if the build complains)
- `app/src/main/java/com/voiceime/VoiceImeService.kt` (onCreateInputView, panelHeight, bottomFiller)
- `app/src/main/java/com/voiceime/ui/T9KeyboardView.kt` (keyHeight, symbolKeyHeight, rows, padding)
- `app/src/main/java/com/voiceime/ui/ClipboardPanelView.kt` (must keep working with a fixed-height panel)
- `app/src/main/java/com/voiceime/ui/VoiceKeyboardView.kt` (must keep working with a fixed-height panel)
- Do NOT run broad Grep/Glob sweeps. Do NOT touch any other file.

## REQUIRED FIX (exactly this, nothing more)
1. DELETE the `bottomFiller` view entirely from `onCreateInputView()` (the View creation + the `root.addView(bottomFiller, ...)` line). It must not exist in any form — no weight-1 filler anywhere in the root layout.
2. DELETE the measure-based panelHeight computation (`keyboard.measure(widthSpec, heightSpec)`, `measuredHeight`, `maxOf(...)`). Do NOT measure the keyboard with an UNSPECIFIED height spec.
3. Compute the panel height by FORMULA, once, in `T9KeyboardView`: expose a public read-only property (e.g. `val panelHeightPx: Int`) returning `paddingTop + paddingBottom + 5 * keyHeight`. The symbol page total (6 * symbolKeyHeight + padding) equals the same value by construction because symbolKeyHeight = keyHeight * 5 / 6 — verify this in code and note it in your summary.
4. In `VoiceImeService.onCreateInputView()`, use `keyboard.panelHeightPx` as the height for ALL THREE panels (keyboard, voiceView, clipboardPanel). Keep top bar 44dp, divider 1dp, panels scrolling internally (ClipboardPanelView and VoiceKeyboardView already use weight-1 ScrollViews inside a fixed-height parent — leave those as they are).
5. Keep the symbol-page compressed key height (`keyHeight * 5 / 6`) as it is. Keep `navigationBarColor` + `isNavigationBarContrastEnforced = false` as they are.
6. Do NOT touch the ASR pipeline, lexicon, candidate bar, key preview popup, vibration, or any other feature. Do NOT change any other behaviour.

## VERIFY BEFORE YOU STOP
- `git diff --stat` shows changes ONLY in `VoiceImeService.kt` and `T9KeyboardView.kt` (plus nothing else).
- `git diff` shows: bottomFiller gone, no `keyboard.measure(` call remains, no `UNSPECIFIED` height measure remains, panel height comes from the formula property.
- Report: the formula value in px AND dp on this machine's display metrics (compute it: keyHeight px, padding px, total px), and confirm the expected total root height (44 + 1 + panel) in dp.

## CONSTRAINTS
- Never print, log, or write any API key, token, or secret anywhere. Never put a key in code or git.
- Kotlin only; keep the existing code style.
