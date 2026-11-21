# VoiceIME 第三轮 B1：高度算法 + 设置面板（含高度调节 + 离线语音包）

## GOAL (only success criterion)
Keyboard height is computed by rows x (key height + gap) with vendor-derived numbers and stays ~316dp panel / 361dp total (or better); top bar has a gear settings button opening a tools panel (same height/logic as clipboard panel) with two tools: keyboard height adjustment (immediate effect, persisted) and offline voice pack download (status shown, downloads on tap only); online voice still uses Doubao + backend correction, offline voice uses the local Sherpa-ONNX pack when present and never crashes when absent.

## OUTPUT-FIRST RULE
Make your FIRST code edit within ~15 tool calls. Reading without writing is not progress. Do not do broad Grep/Glob sweeps. Only read the files named below (plus the files you must edit). If stuck for more than 15 calls without an edit, stop and report. Work in order E -> F -> G; commit after each part; DO NOT push (the next round pushes).

## Context (already verified by coordinator, do not re-investigate)
- Round 3a (just before you) committed: Chunk 1 popup + 修改 button, and Part A SemanticCorrector + wiring. Verify with `git log --oneline -5` first — its commits must be there. Do NOT reset, do NOT touch its changes.
- Current height (user-confirmed normal): panel 316dp = paddingTop 2dp + paddingBottom 4dp + 5 x keyHeight 62dp; root total 361dp (44dp top bar + 1dp divider + 316dp panel). T9 grid is 5 rows (4 key rows + bottom row). Do NOT break this: after your change the default height must be the same 316dp/361dp or better (never taller than now at default).
- REA numbers (from REA_COMPARISON.md section 3; algorithm + numbers only, NO vendor code, NO vendor packages):
  - Doubao: key height 44 + gap 8 = 52 per row; total height = rows x (key height + gap) — computed, never measured; 9-key 3-column structure; left column holds the pinyin segmentation list.
  - WeChat S1 9-key (1080px width baseline, 1080px = 360dp so divide px by 3): total 628px = ~209dp, 4 rows, row height 140px = ~47dp, row spacing [10,8,8,8,10]px, key width 210px = 70dp, key height 139px = ~46dp, main font 50-56px = ~17-19sp, digit sub-label 26px = ~9sp top-right, corner radius 16px = ~5dp.
- Top bar now: candidate bar + clipboard icon + keyboard/voice toggle at the far RIGHT. Clipboard panel opens from the top bar and swaps in place of the keyboard panel (same height/logic) — read how it is shown/hidden in VoiceImeService.kt first.
- Voice flow now: startRecording -> DoubaoAsrClient (online); onInterim -> popup text; onAsrFinal -> SemanticCorrector -> commitOnce. isNetworkAvailable() already exists in the service.
- Do NOT touch: ASR pipeline (DoubaoAsrClient, AudioCapture, AsrProtocol) except the branching Part G needs, lexicon, typing keyboard Chinese path, clipboard, colors except where a part needs it.

## Part E — Height by formula, vendor numbers (do first)
1. Read T9KeyboardView.kt (panelHeightPx) and VoiceImeService.kt (panelHeight use) first.
2. Replace the hardcoded height with the formula: panelHeight = paddingTop + paddingBottom + rows x (keyHeight + keyGap). Pick keyHeight/keyGap so the 5-row T9 panel stays 316dp at default (e.g. keyHeight 58 + gap 4 = 62 pitch -> 310 + 6 = 316, identical to now). Adopt vendor numbers where they fit: key corner radius ~5dp, main key font ~18sp, digit sub-label ~9sp top-right, key gap 4-8dp. Record the exact numbers you chose in the commit message.
3. Symbols page (6 rows) and voice panel must keep working with the same formula (rows x pitch + padding).
4. Commit Part E separately.

## Part F — Settings gear + keyboard height tool
1. Read the top-bar code in VoiceImeService.kt first. Add a gear settings button at the FAR LEFT of the top bar (clipboard + toggle stay far right). Render it the same way the existing top-bar icons are rendered; if a drawable is needed, add res/drawable/ic_settings.xml (a simple gear vector, drawn by you — no vendor assets).
2. Tap gear -> show a settings panel exactly like the clipboard panel (same container, same height, same show/hide logic), but its content is a tools list. Tapping gear again, or switching to keyboard/clipboard, closes it — same as clipboard behavior.
3. First tool: "键盘高度" with a SeekBar (or +/- buttons). Changing it updates the panel height IMMEDIATELY (recompute via Part E's formula and re-layout the current panel). It adjusts keyHeight (range about 52-72dp, default = the Part E value); panel height follows the formula. Persist the chosen keyHeight in SharedPreferences; on next keyboard open, use the stored value.
4. Settings panel must not disturb voice popup / correction.
5. Commit Part F separately.

## Part G — Offline voice pack (Sherpa-ONNX Paraformer, download on demand only)
1. Settings panel (from Part F) gets a second tool: "离线语音包" with a status line: 未下载 / 下载中 xx% / 已下载. NEVER bundle the model in the APK, NEVER auto-download.
2. Tap download (only when online; if offline, toast that downloading needs network): download the file sherpa-onnx-paraformer-trilingual-zh-cantonese-en.tar.bz2 (int8, ~234MB, Chinese + English + Cantonese) from GitHub Releases (k2-fsa/sherpa-onnx, asset name as above — verify the exact release URL from the sherpa-onnx repo first, do not guess a different asset); if GitHub fails, retry via the hub.nuaa.cf mirror with the same path. Stream to internal storage (getFilesDir()/offline-asr/), show progress in the status line, verify the downloaded size after finishing, extract the tar.bz2 (add org.apache.commons:commons-compress for tar+bz2 if not present), verify the expected model files exist (encoder.int8.onnx, decoder.int8.onnx, tokens.txt — confirm names from what was actually extracted), delete the archive after success, update status to 已下载.
3. Voice flow: in startRecording, if there is NO network and the offline pack is present, use the local path: Sherpa-ONNX OfflineRecognizer with the local model dir (add the official sherpa-onnx Android library from Maven Central — check the artifact coordinates from the sherpa-onnx docs; if the artifact cannot be resolved, stop this part and report it, do not fake it). Feed mic PCM from the same AudioCapture (16kHz mono) via acceptSamples; interim/final results go into the same popup; popup status shows "离线识别" so the user knows accuracy will be lower. Final result commits the RAW text directly — NO backend correction offline (correction needs network).
4. If there is NO network and NO pack: do not crash, do not hang, do not start recording — show the voice panel/popup with a message telling the user to download the pack in Settings when online.
5. Online path unchanged: Doubao cloud ASR + backend correction as in Part A.
6. Commit Part G separately.

## Rules
- Never print, log, or write any API key / PAT value. The Doubao key stays via BuildConfig secret as before; the correction backend needs no key in the app.
- Do NOT run gradle locally (no Android SDK on this Box). Compilation is verified by GitHub Actions after push.
- No broad exploration. No changes outside: T9KeyboardView.kt, VoiceImeService.kt, new SettingsPanel view if needed, new OfflineAsr files if needed, res/drawable/ic_settings.xml, app/build.gradle.kts (dependencies only), strings if a new label is needed.
