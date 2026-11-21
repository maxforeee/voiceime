package com.voiceime.ui

import android.content.Context
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.voiceime.R

/**
* T9 grid, built programmatically (no layout inflation, keeps popup fast).
* Emits raw key codes; all input-state logic lives in VoiceImeService.
*
* Main page (5 rows): 1 / 2abc / 3def | 4ghi / 5jkl / 6mno | 7pqrs / 8tuv / 9wxyz
* 中/英 / 0 / ⌫ | 符号 / 空格 / 回车
* Symbol page (5 rows): 4 rows x 6 symbol cells in a table grid (no button
* frame, no digits - digits live on the 9-key page) +
* 返回 / 空格 / 回车, same size as the main bottom row.
*
* Each keyboard scales with its own row pitch from Settings (two
* sliders since v1.2.9): key height, key gap, main / small text sizes
* and the 9-key sublabel (0.65x main, relative) all derive from that
* page pitch, so no key or label is ever left behind or clipped at
* any height setting.
*/
class T9KeyboardView(context: Context): LinearLayout(context) {

var onKey: ((String) -> Unit)? = null
var onDeleteLongPress: (() -> Unit)? = null

/** Swipe up on a digit key: input that digit directly, no pinyin. */
var onDigitSwipeUp: ((String) -> Unit)? = null

// Row pitches (key height + vertical gap) in px, one per keyboard:
// the two keyboards size independently now (v1.2.9), each from its
// own Settings slider. Every size below derives from its own pitch.
private var t9PitchPx: Int
private var qwertyPitchPx: Int
/** Pitch of the page currently being built: text sizes derive from
 * it, so T9 and QWERTY labels each scale with their own pitch. */
private var buildPitchPx: Int

private var showingSymbols = false
private var showingZh = true
private var showingQwerty = false
private var qwertyShift = false
private var qwertyCaps = false
private var lastShiftTapMs = 0L

/** Height scale vs the 62dp reference pitch (1.0 = the original sizes). */
private val scale: Float
get() = (buildPitchPx / resources.displayMetrics.density / BASE_ROW_PITCH_DP)
.coerceIn(0.6f, 1.25f)

/** T9 key/gap (v1.2.9: 40:12, was Doubao 44:8): the visible gap
* between two keys is 2 x t9GapPx = t9Pitch x 12/52, so at the 52dp
* pitch the gap is 12dp and the key is 40dp -- the T9 keys were
* filling too much of the panel at 44:8 (85%), now 40/52 = 77%. */
private val t9GapPx: Int
get() = ((t9PitchPx * 6f / 52f) + 0.5f).toInt().coerceAtLeast(context.dp(2f))
private val t9KeyGap: Int get() = t9GapPx * 2

/** QWERTY key/gap (Doubao 44:8; the Doubao English26 skin rows are
* height_weight 48 against the 9-key 52): the visible gap is
* 2 x qwertyGapPx = qwertyPitch x 8/52, so at the 48dp default pitch
* the gap is ~7.4dp and the key ~40.6dp. */
private val qwertyGapPx: Int
get() = ((qwertyPitchPx * 4f / 52f) + 0.5f).toInt().coerceAtLeast(context.dp(2f))
private val qwertyKeyGap: Int get() = qwertyGapPx * 2

// Visible key heights. The row pitch adds the vertical gap (top +
// bottom key margins), so each panel height is rows x its own pitch.
private val keyHeight: Int get() = t9PitchPx - t9KeyGap
private val qwertyKeyHeightPx: Int get() = qwertyPitchPx - qwertyKeyGap

/** Main key label size: 18sp at reference pitch, scaled with the page
* pitch. The 9-key digit and its letters share this size on one line,
* so the label shrinks with the key and can never overflow it at the
* lowest pitch (35dp: ~10.8sp fits a ~27dp key). */
private val mainTextSizeSp: Float get() = 18f * scale
/** Narrow bottom-row labels (lang / symbol on the QWERTY bottom row):
* 15sp at reference pitch, scaled with it. */
private val smallTextSizeSp: Float get() = 15f * scale

/** Fixed height of the 5-row T9 page, shared by the symbol / voice /
* clipboard / settings panels: 5 x T9 pitch plus vertical padding. */
val panelHeightPx: Int
get() = paddingTop + paddingBottom + 5 * t9PitchPx

/** Height of the page currently shown: QWERTY is 4 rows of its own
* pitch (Doubao English26 rows are 48 against the 9-key 52, so it
* runs a little shorter than T9); symbols keep the 5-row T9 height. */
val currentPanelHeightPx: Int
get() = paddingTop + paddingBottom +
if (showingQwerty) 4 * qwertyPitchPx else 5 * t9PitchPx

companion object {
private const val PREFS = "voiceime_prefs"
private const val KEY_ROW_PITCH_DP = "row_pitch_dp"
private const val KEY_QWERTY_ROW_PITCH_DP = "qwerty_row_pitch_dp"
const val MIN_ROW_PITCH_DP = 35f
const val MAX_ROW_PITCH_DP = 72f
/** Defaults from the Doubao skin: T9 rows 52 (key 44 + gap 8),
* English26 rows 48 against the 9-key 52. T9 now runs 40:12
* (key 40 + gap 12 at the 52dp pitch); English stays 44:8. */
const val DEFAULT_T9_ROW_PITCH_DP = 52f
const val DEFAULT_QWERTY_ROW_PITCH_DP = 48f
/** Reference pitch: every size below is defined at 62dp and scaled
* from it, so the keyboard keeps its proportions at any setting. */
private const val BASE_ROW_PITCH_DP = 62f
/** T9 key area is squeezed a little toward the centre: this extra
* side inset on top of the key gap, each side, so the keys no
* longer run right to the screen edges. */
private const val T9_SIDE_INSET_EXTRA_DP = 4f
}

/** Current T9 row pitch in dp, for the Settings panel label. */
fun currentRowPitchDp(): Float =
t9PitchPx / resources.displayMetrics.density

/** Current QWERTY row pitch in dp, for the Settings panel label. */
fun currentQwertyRowPitchDp(): Float =
qwertyPitchPx / resources.displayMetrics.density

/** Apply a new T9 row pitch from Settings: persist, rebuild the T9
* page with the new height. Panel height follows the formula, so the
* service re-reads panelHeightPx after calling this. */
fun setRowPitchDp(dpValue: Float) {
val clamped = dpValue.coerceIn(MIN_ROW_PITCH_DP, MAX_ROW_PITCH_DP)
context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
.putFloat(KEY_ROW_PITCH_DP, clamped).apply()
t9PitchPx = context.dp(clamped)
buildPitchPx = t9PitchPx
if (showingSymbols) showSymbols() else if (!showingQwerty) showMain(showingZh)
}

/** Apply a new QWERTY row pitch from Settings: persist, rebuild the
* English page if it is showing. */
fun setQwertyRowPitchDp(dpValue: Float) {
val clamped = dpValue.coerceIn(MIN_ROW_PITCH_DP, MAX_ROW_PITCH_DP)
context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
.putFloat(KEY_QWERTY_ROW_PITCH_DP, clamped).apply()
qwertyPitchPx = context.dp(clamped)
buildPitchPx = qwertyPitchPx
if (showingQwerty) showQwerty()
}

init {
orientation = VERTICAL
setBackgroundColor(context.getColor(R.color.keyboard_bg))
// Saved heights win; a fresh install starts at the Doubao
// defaults (T9 52dp, English 48dp). The two pitches are
// independent: changing one never moves the other.
val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
val savedT9 = prefs.getFloat(KEY_ROW_PITCH_DP, -1f)
val savedQwerty = prefs.getFloat(KEY_QWERTY_ROW_PITCH_DP, -1f)
t9PitchPx = if (savedT9 > 0f) context.dp(savedT9) else context.dp(DEFAULT_T9_ROW_PITCH_DP)
qwertyPitchPx = if (savedQwerty > 0f) context.dp(savedQwerty) else context.dp(DEFAULT_QWERTY_ROW_PITCH_DP)
buildPitchPx = t9PitchPx
applyT9Padding()
}

/** Side padding for the T9 pages: the key gap plus a small extra
* inset, so the key area sits a little in from the screen edges. */
private fun applyT9Padding() {
val side = t9GapPx + context.dp(T9_SIDE_INSET_EXTRA_DP)
setPadding(side, context.dp(2f), side, context.dp(4f))
}

/** Side padding for the QWERTY page: just the key gap, as before. */
private fun applyQwertyPadding() {
setPadding(qwertyGapPx, context.dp(2f), qwertyGapPx, context.dp(4f))
}

/** Show the 9-key page for Chinese, the full QWERTY page for English. */
fun showMainForLang(zh: Boolean) {
if (zh) showMain(true) else showQwerty()
}

fun showMain(zh: Boolean) {
removeAllViews()
showingSymbols = false
showingQwerty = false
showingZh = zh
buildPitchPx = t9PitchPx
applyT9Padding()
val sub1 = if (zh) "，" else null
row(
digitKey("1", sub1), digitKey("2", "ABC"), digitKey("3", "DEF")
)
row(
digitKey("4", "GHI"), digitKey("5", "JKL"), digitKey("6", "MNO")
)
row(
digitKey("7", "PQRS"), digitKey("8", "TUV"), digitKey("9", "WXYZ")
)
row(
textKey("符号", "sym", function = true),
digitKey("0", null),
textKey("⌫", "del", function = true)
)
row(
textKey(if (zh) "中/英" else "英/中", "lang", function = true),
textKey("空格", "space"),
textKey("回车", "enter", function = true),
weights = floatArrayOf(1f, 2f, 1f)
)
}

/** Symbol page: 24 symbols in a 4 x 6 table grid (flat cells, no button
* frame), then the bottom row at full row height with the same widths
* as the main bottom row (返回 1 / 空格 2 / 回车 1). No digits: digits
* live on the 9-key page (tap = pinyin, swipe up = digit). Symbol
* cells use the same scaled text size and row pitch as every other
* key, so they scale with the Settings height too. */
fun showSymbols() {
removeAllViews()
showingSymbols = true
showingQwerty = false
buildPitchPx = t9PitchPx
applyT9Padding()
gridRow(
symbolCell("，"), symbolCell("。"), symbolCell("、"),
symbolCell("；"), symbolCell("："), symbolCell("？")
)
gridRow(
symbolCell("！"), symbolCell("“"), symbolCell("”"),
symbolCell("‘"), symbolCell("’"), symbolCell("（")
)
gridRow(
symbolCell("）"), symbolCell("《"), symbolCell("》"),
symbolCell(""), symbolCell("·")
)
gridRow(
symbolCell("…"), symbolCell("—"), symbolCell("～"),
symbolCell("＠"), symbolCell("＃"), symbolCell("％")
)
row(
textKey("返回", "back", function = true),
textKey("空格", "space"),
textKey("回车", "enter", function = true),
weights = floatArrayOf(1f, 2f, 1f)
)
}

/** Full 26-key QWERTY for English (Chinese stays 9-key). Four rows of
* its own pitch (Doubao style: key 44 + gap 8 = 52 per row, total
* 4 x its own pitch, default 48dp). Gboard layout: 10 / 9 / 7 letters,
* then the bottom row. Shift opens the z row and delete closes it, both
* about two letter keys wide so the row is not cramped; the bottom row
* is lang / symbol / space / enter with uniform non-space widths. */
fun showQwerty() {
removeAllViews()
showingSymbols = false
showingQwerty = true
buildPitchPx = qwertyPitchPx
applyQwertyPadding()
val upper = qwertyShift || qwertyCaps
// QWERTY runs its own pitch (default 48dp): key 44/52 x pitch,
// gap 8/52 x pitch, total 4 x pitch. Keys are not stretched.
val h = qwertyKeyHeightPx
val g = qwertyKeyGap
fun letter(ch: Char): TextView {
val s = if (upper) ch.uppercaseChar().toString() else ch.toString()
return textKey(s, s)
}
addView(vGapView(g - paddingTop))
row(*"qwertyuiop".map { letter(it)}.toTypedArray(), height = h, hGap = qwertyGapPx, vGap = 0)
addView(vGapView(g))
row(
spacer(),
*"asdfghjkl".map { letter(it)}.toTypedArray(),
spacer(),
height = h, hGap = qwertyGapPx, vGap = 0,
weights = floatArrayOf(0.5f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 0.5f)
)
addView(vGapView(g))
row(
textKey(if (qwertyCaps) "\u21EA" else "\u21E7", "shift", function = true),
*"zxcvbnm".map { letter(it)}.toTypedArray(),
textKey("\u232B", "del", function = true),
height = h, hGap = qwertyGapPx, vGap = 0,
weights = floatArrayOf(2f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 2f)
)
addView(vGapView(g))
row(
textKey("\u4E2D/\u82F1", "lang", function = true, textSizeSp = smallTextSizeSp, singleLine = true),
textKey("\u7B26\u53F7", "sym", function = true, textSizeSp = smallTextSizeSp, singleLine = true),
textKey("\u7A7A\u683C", "space"),
textKey("\u56DE\u8F66", "enter", function = true),
height = h, hGap = qwertyGapPx, vGap = 0,
weights = floatArrayOf(1.5f, 1.5f, 5f, 2f)
)
addView(vGapView(g - paddingBottom))
}

/** Shift: one tap = one-shot uppercase, second quick tap = caps lock,
* tap while caps is on = back to lowercase. */
fun toggleQwertyShift() {
val now = SystemClock.uptimeMillis()
when {
qwertyCaps -> { qwertyCaps = false; qwertyShift = false}
qwertyShift && now - lastShiftTapMs < 400 -> { qwertyShift = false; qwertyCaps = true}
else -> qwertyShift = true
}
lastShiftTapMs = now
if (showingQwerty) showQwerty()
}

/** After a one-shot shifted letter is committed, drop back to lowercase. */
fun consumeQwertyShiftIfOneShot() {
if (qwertyShift &&!qwertyCaps) {
qwertyShift = false
if (showingQwerty) showQwerty()
}
}

/** Invisible spacer: takes width in a row without drawing anything. */
/** Empty vertical gap view: one of the five equal QWERTY gaps. */
private fun vGapView(height: Int): View =
View(context).apply { layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, height) }

private fun spacer(): TextView =
TextView(context).apply { visibility = View.INVISIBLE}

private fun row(vararg keys: TextView, height: Int = t9PitchPx, weights: FloatArray? = null, hGap: Int = t9GapPx, vGap: Int = t9GapPx) {
val row = LinearLayout(context).apply { orientation = HORIZONTAL}
for ((i, k) in keys.withIndex()) {
row.addView(
k,
LayoutParams(0, LayoutParams.MATCH_PARENT, weights?.getOrNull(i)?: 1f).apply {
setMargins(hGap, vGap, hGap, vGap)
}
)
}
addView(row, LayoutParams(LayoutParams.MATCH_PARENT, height))
}

/** One row of the symbol grid: hairline gaps over the divider-coloured
* row background read as table lines; cells carry no button frame. */
private fun gridRow(vararg keys: TextView, height: Int = t9PitchPx) {
val row = LinearLayout(context).apply {
orientation = HORIZONTAL
setBackgroundColor(context.getColor(R.color.divider))
}
for (k in keys) {
row.addView(
k,
LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
setMargins(1, 1, 1, 1)
}
)
}
addView(row, LayoutParams(LayoutParams.MATCH_PARENT, height))
}

/** One symbol cell: flat cell background (no button frame, no press
* shrink), same scaled text size as every other key. */
private fun symbolCell(symbol: String): TextView =
baseKey(symbol, symbol, cell = true, pressScale = false)

private fun digitKey(digit: String, letters: String?): TextView {
// Digit on the left, letters on the right, one line, same size
// (e.g. "2 ABC"); letters keep the secondary colour. No sublabel
// (the 0 key) stays a centred digit.
val label = if (letters == null) {
SpannableStringBuilder(digit)
} else {
SpannableStringBuilder("$digit $letters").apply {
// Follows the theme (light grey / dark grey) via key_text_secondary.
setSpan(
ForegroundColorSpan(context.getColor(R.color.key_text_secondary)),
digit.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
)
}
}

return baseKey(label, digit, singleLine = true)
}

private fun textKey(label: String, code: String, function: Boolean = false, textSizeSp: Float? = null, singleLine: Boolean = false): TextView =
baseKey(label, code, function, textSizeSp, singleLine)

private fun baseKey(label: CharSequence, code: String, function: Boolean = false, textSizeSp: Float? = null, singleLine: Boolean = false, cell: Boolean = false, pressScale: Boolean = true): TextView {
val tv = TextView(context)
tv.gravity = Gravity.CENTER
tv.text = label
tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp?: mainTextSizeSp)
// Narrow function labels (e.g. lang / symbol on the QWERTY bottom
// row) must stay on one line instead of wrapping into two.
if (singleLine) tv.maxLines = 1
tv.setTextColor(context.getColor(R.color.key_text))
tv.background = if (cell) cellBackground(context) else keyBackground(context, function)
tv.isClickable = true
tv.isHapticFeedbackEnabled = true
// Press effect: the key shrinks slightly while held and springs back
// on release. No popup preview (removed at the user's request).
// Returning false keeps the TextView's own press/click handling intact.
var longPressed = false
var swipedUp = false
var downY = 0f
tv.setOnTouchListener { v, event ->
when (event.actionMasked) {
MotionEvent.ACTION_DOWN -> {
downY = event.y
swipedUp = false
v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
if (pressScale) {
v.scaleX = 0.95f
v.scaleY = 0.95f
}
}
MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
if (pressScale) {
v.scaleX = 1f
v.scaleY = 1f
}
// Swipe up on a digit key inputs that digit directly.
if (event.actionMasked == MotionEvent.ACTION_UP &&
code.length == 1 && code[0] in '0'..'9' &&
downY - event.y > context.dp(24f)
) {
swipedUp = true
onDigitSwipeUp?.invoke(code)
}
}
}
false
}
if (code == "del") {
tv.setOnLongClickListener {
it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
onDeleteLongPress?.invoke()
longPressed = true
true
}
}
tv.setOnClickListener {
// A long-press already handled this tap; skip the extra delete.
// A swipe-up already committed the digit; skip the pinyin tap.
if (longPressed) longPressed = false
else if (swipedUp) swipedUp = false
else onKey?.invoke(code)
}
return tv
}

}
