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
* Everything scales with the row pitch from Settings: key height, key gap,
* main / small text sizes and the 9-key sublabel (0.65x main, relative) all
* derive from one scale factor, so no key or label is ever left behind or
* clipped at any height setting.
*/
class T9KeyboardView(context: Context): LinearLayout(context) {

var onKey: ((String) -> Unit)? = null
var onDeleteLongPress: (() -> Unit)? = null

/** Swipe up on a digit key: input that digit directly, no pinyin. */
var onDigitSwipeUp: ((String) -> Unit)? = null

// Row pitch (key height + vertical gap) in px, from Settings or auto.
// Every size below derives from it, so the whole keyboard scales as one.
private var pitchPx: Int

private var showingSymbols = false
private var showingZh = true
private var showingQwerty = false
private var qwertyShift = false
private var qwertyCaps = false
private var lastShiftTapMs = 0L

/** Height scale vs the 62dp reference pitch (1.0 = the original sizes). */
private val scale: Float
get() = (pitchPx / resources.displayMetrics.density / BASE_ROW_PITCH_DP)
.coerceIn(0.6f, 1.25f)

/** Half of the visible key gap (Doubao 44:8): the visible gap between
* two keys is 2 x gapPx = rowPitch x 8/52, so at the 52dp pitch the gap
* is 8dp and the key is 44dp, exactly Doubao skin numbers (key 44 +
* gap 8 = 52 per row). Doubao own horizontal margin is 2.5dp a side
* (5dp gap); ours is never smaller than that. */
private val gapPx: Int
get() = ((pitchPx * 4f / 52f) + 0.5f).toInt().coerceAtLeast(context.dp(2f))
private val keyGap: Int get() = gapPx * 2

/** QWERTY key height: Doubao ratio 44/52 x rowPitch (key 44 + gap 8 =
* 52 per row at the 52dp pitch). The panel total is 4 rows, one row
* shorter than the 5-row T9 page. */
private val qwertyKeyHeightPx: Int
    get() = (rowPitch * QWERTY_KEY_HEIGHT_FACTOR).toInt()

/** QWERTY vertical gap: the Doubao gap, same as the T9 gap (keyGap =
* rowPitch x 8/52; 8dp at the 52dp pitch). Between rows it is one gap
* view; top/bottom edges are gap view minus the view own padding, so
* every vertical whitespace on this page is the same size. Horizontal
* gaps are 2 x gapPx, the same value. */
private val qwertyVGapPx: Int
    get() = keyGap

// Visible key height. The row pitch adds the vertical gap (top + bottom
// key margins), so the panel height is rows x (keyHeight + keyGap).
private val keyHeight: Int get() = pitchPx - keyGap
private val rowPitch: Int get() = pitchPx

/** Main key label size: 18sp at reference pitch, scaled with it. The
* 9-key digit and its letters share this size on one line, so the
* label shrinks with the key and can never overflow it at the lowest
* pitch (45dp: 13.1sp fits a 39dp key with room to spare). */
private val mainTextSizeSp: Float get() = 18f * scale
/** Narrow bottom-row labels (lang / symbol on the QWERTY bottom row):
* 15sp at reference pitch, scaled with it. */
private val smallTextSizeSp: Float get() = 15f * scale

/** Fixed height of the 5-row T9 page, shared by the symbol / voice /
* clipboard / settings panels: rows x (keyHeight + keyGap) plus
* vertical padding. */
val panelHeightPx: Int
get() = paddingTop + paddingBottom + 5 * rowPitch

/** QWERTY row pitch: one row pitch per row, same pitch as T9. The 4
* English rows total 4 x rowPitch, exactly one row shorter than the
* 5-row T9 page (Doubao does the same). The keys keep their natural
* height (see qwertyKeyHeightPx), never stretched to fill. */
private val qwertyRowPitch: Int get() = rowPitch

/** Height of the page currently shown: QWERTY is 4 rows (one row
* shorter than T9 5); symbols keep the 5-row height. */
val currentPanelHeightPx: Int
get() = paddingTop + paddingBottom +
if (showingQwerty) 4 * qwertyRowPitch else 5 * rowPitch

companion object {
private const val PREFS = "voiceime_prefs"
private const val KEY_ROW_PITCH_DP = "row_pitch_dp"
const val MIN_ROW_PITCH_DP = 45f
const val MAX_ROW_PITCH_DP = 72f
/** Reference pitch: every size below is defined at 62dp and scaled
* from it, so the keyboard keeps its proportions at any setting. */
private const val BASE_ROW_PITCH_DP = 62f
    /** QWERTY key-height factor: Doubao ratio, key 44 of a 52 row pitch. */
    private const val QWERTY_KEY_HEIGHT_FACTOR = 44f / 52f
}

/** Current row pitch in dp, for the Settings panel label. */
fun currentRowPitchDp(): Float =
rowPitch / resources.displayMetrics.density

/** Apply a new row pitch from Settings: persist, rebuild the current
* page with the new height. Panel height follows the formula, so the
* service re-reads panelHeightPx after calling this. */
fun setRowPitchDp(dpValue: Float) {
val clamped = dpValue.coerceIn(MIN_ROW_PITCH_DP, MAX_ROW_PITCH_DP)
context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
.putFloat(KEY_ROW_PITCH_DP, clamped).apply()
pitchPx = context.dp(clamped)
if (showingSymbols) showSymbols() else if (showingQwerty) showQwerty() else showMain(showingZh)
}

init {
orientation = VERTICAL
setBackgroundColor(context.getColor(R.color.keyboard_bg))
val dm = resources.displayMetrics
val landscape = dm.widthPixels > dm.heightPixels
// Auto height: scale row pitch with the screen, clamped to sane
// touch sizes; the visible key takes the pitch minus the gap.
// A height saved in Settings overrides the auto value.
val target = if (landscape) dm.heightPixels * 0.085f else dm.heightPixels * 0.062f
val autoPitch = target.toInt().coerceIn(context.dp(42f), context.dp(62f))
val savedPitch = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
.getFloat(KEY_ROW_PITCH_DP, -1f)
pitchPx = if (savedPitch > 0f) context.dp(savedPitch) else autoPitch
setPadding(gapPx, context.dp(2f), gapPx, context.dp(4f))
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

/** Full 26-key QWERTY for English (Chinese stays 9-key). Four rows,
* one row shorter than the 5-row T9 page (Doubao style: key 44 + gap 8
* = 52 per row, total 4 x rowPitch). Gboard layout: 10 / 9 / 7 letters,
* then the bottom row. Shift opens the z row and delete closes it, both
* about two letter keys wide so the row is not cramped; the bottom row
* is lang / symbol / space / enter with uniform non-space widths. */
fun showQwerty() {
removeAllViews()
showingSymbols = false
showingQwerty = true
val upper = qwertyShift || qwertyCaps
// Doubao 44:8 -- key 44/52 x rowPitch, gap 8/52 x rowPitch, total
// 4 x rowPitch, one row shorter than T9. Keys are not stretched.
val h = qwertyKeyHeightPx
val g = qwertyVGapPx
fun letter(ch: Char): TextView {
val s = if (upper) ch.uppercaseChar().toString() else ch.toString()
return textKey(s, s)
}
addView(vGapView(g - paddingTop))
row(*"qwertyuiop".map { letter(it)}.toTypedArray(), height = h, vGap = 0)
addView(vGapView(g))
row(
spacer(),
*"asdfghjkl".map { letter(it)}.toTypedArray(),
spacer(),
height = h, vGap = 0,
weights = floatArrayOf(0.5f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 0.5f)
)
addView(vGapView(g))
row(
textKey(if (qwertyCaps) "\u21EA" else "\u21E7", "shift", function = true),
*"zxcvbnm".map { letter(it)}.toTypedArray(),
textKey("\u232B", "del", function = true),
height = h, vGap = 0,
weights = floatArrayOf(2f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 2f)
)
addView(vGapView(g))
row(
textKey("\u4E2D/\u82F1", "lang", function = true, textSizeSp = smallTextSizeSp, singleLine = true),
textKey("\u7B26\u53F7", "sym", function = true, textSizeSp = smallTextSizeSp, singleLine = true),
textKey("\u7A7A\u683C", "space"),
textKey("\u56DE\u8F66", "enter", function = true),
height = h, vGap = 0,
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

private fun row(vararg keys: TextView, height: Int = rowPitch, weights: FloatArray? = null, hGap: Int = gapPx, vGap: Int = gapPx) {
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
private fun gridRow(vararg keys: TextView, height: Int = rowPitch) {
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
