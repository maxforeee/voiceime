package com.voiceime.ui

import android.content.Context
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
 * One keyboard only (v1.3.0): the 9-key page serves both Chinese and
 * English. Switching language is seamless — same keys, same positions,
 * same sizes. The ONLY difference is the punctuation next to the 1 key:
 * a Chinese comma (，) in Chinese mode, a full stop (.) in English mode.
 * The 中/英 toggle key itself always reads "中/英".
 *
 * Main page (5 rows): 1 / 2abc / 3def | 4ghi / 5jkl / 6mno | 7pqrs / 8tuv / 9wxyz
 * 符号 / 0 / ⌫ | 中/英 / 空格 / 回车
 * Symbol page (5 rows): 4 rows x 6 symbol cells in a table grid (no button
 * frame, no digits - digits live on the 9-key page) +
 * 返回 / 空格 / 回车, same size as the main bottom row.
 *
 * Key size (v1.3.0, Settings "按键大小"): the total keyboard height is
 * fixed; growing the keys squeezes the gaps around them (key height up,
 * gap down, total height unchanged, keyboard centred). The visible gap
 * never goes below 2dp, so keys never touch.
 *
 * Every size derives from the row pitch, so no key or label is ever left
 * behind or clipped at any height setting.
 */
class T9KeyboardView(context: Context) : LinearLayout(context) {

    var onKey: ((String) -> Unit)? = null
    var onDeleteLongPress: (() -> Unit)? = null

    /** Swipe up on a digit key: input that digit directly, no pinyin. */
    var onDigitSwipeUp: ((String) -> Unit)? = null

    // Row pitch (key height + vertical gap) in px. One pitch for the one
    // keyboard (v1.3.0); every size below derives from it.
    private var t9PitchPx: Int

    private var showingSymbols = false
    private var showingZh = true

    /** Extra key height from the "按键大小" setting, in px. 0 = the base
     *  size (key 40 + gap 12 at the 52dp pitch). Growing it shrinks the
     *  gaps by the same total, so the row pitch never changes. */
    private var keyExtraPx: Int = 0

    /** Height scale vs the 62dp reference pitch (1.0 = the original sizes). */
    private val scale: Float
        get() = (t9PitchPx / resources.displayMetrics.density / BASE_ROW_PITCH_DP)
            .coerceIn(0.6f, 1.25f)

    /** T9 key/gap (v1.2.9: 40:12): the visible gap between two keys is
     * 2 x t9GapPx = t9Pitch x 12/52, so at the 52dp pitch the gap is 12dp
     * and the key is 40dp. */
    private val t9GapPx: Int
        get() = ((t9PitchPx * 6f / 52f) + 0.5f).toInt().coerceAtLeast(context.dp(2f))
    private val t9KeyGap: Int get() = t9GapPx * 2

    /** Effective gap after the key-size setting: the keys grow outward
     *  from the centre, squeezing the gaps, but never below a 2dp visible
     *  gap (1dp per side), so keys never touch. */
    private val effGapPx: Int
        get() = (t9GapPx - keyExtraPx / 2).coerceAtLeast(context.dp(1f))

    // Visible key heights. The row pitch adds the vertical gap (top +
    // bottom key margins), so each panel height is rows x its own pitch.
    private val keyHeight: Int get() = t9PitchPx - t9KeyGap

    /** Main key label size: 18sp at reference pitch, scaled with the page
     * pitch. The 9-key digit and its letters share this size on one line,
     * so the label shrinks with the key and can never overflow it at the
     * lowest pitch (35dp: ~10.8sp fits a ~27dp key). */
    private val mainTextSizeSp: Float get() = 18f * scale
    /** Narrow bottom-row labels (lang / symbol): 15sp at reference pitch,
     * scaled with it. */
    private val smallTextSizeSp: Float get() = 15f * scale

    /** Fixed height of the 5-row T9 page, shared by the symbol / voice /
     * clipboard / settings panels: 5 x T9 pitch plus vertical padding. */
    val panelHeightPx: Int
        get() = paddingTop + paddingBottom + 5 * t9PitchPx

    /** Height of the page currently shown: always the 5-row T9 height
     * (v1.3.0: one keyboard, one pitch). */
    val currentPanelHeightPx: Int
        get() = paddingTop + paddingBottom + 5 * t9PitchPx

    companion object {
        private const val PREFS = "voiceime_prefs"
        private const val KEY_ROW_PITCH_DP = "row_pitch_dp"
        private const val KEY_KEY_EXTRA_DP = "key_size_extra_dp"
        const val MIN_ROW_PITCH_DP = 35f
        const val MAX_ROW_PITCH_DP = 72f
        /** Default from the Doubao skin: T9 rows 52 (key 44 + gap 8).
         * T9 now runs 40:12 (key 40 + gap 12 at the 52dp pitch). */
        const val DEFAULT_T9_ROW_PITCH_DP = 52f
        /** Reference pitch: every size below is defined at 62dp and scaled
         * from it, so the keyboard keeps its proportions at any setting. */
        private const val BASE_ROW_PITCH_DP = 62f
        /** T9 key area is squeezed a little toward the centre: this extra
         * side inset on top of the key gap, each side, so the keys no
         * longer run right to the screen edges. */
        private const val T9_SIDE_INSET_EXTRA_DP = 4f
        /** Minimum visible gap between two keys (v1.3.0 key size): keys
         * never touch, even at the largest key size. */
        private const val MIN_VISIBLE_GAP_DP = 2f
    }

    /** Current T9 row pitch in dp, for the Settings panel label. */
    fun currentRowPitchDp(): Float =
        t9PitchPx / resources.displayMetrics.density

    /** Current key-size extra in dp, for the Settings panel label. */
    fun currentKeyExtraDp(): Float =
        keyExtraPx / resources.displayMetrics.density

    /** Largest key-size extra at the current pitch: the gap may shrink to
     *  the 2dp minimum but never below it. Depends on the pitch, so the
     *  Settings panel re-reads it after a height change. */
    fun maxKeyExtraDp(): Float {
        val gapDp = t9GapPx / resources.displayMetrics.density
        return (2f * (gapDp - MIN_VISIBLE_GAP_DP / 2f)).coerceAtLeast(0f)
    }

    /** Apply a new T9 row pitch from Settings: persist, rebuild the page
     *  with the new height. Panel height follows the formula, so the
     *  service re-reads panelHeightPx after calling this. The key-size
     *  extra is clamped to the new pitch's maximum. */
    fun setRowPitchDp(dpValue: Float) {
        val clamped = dpValue.coerceIn(MIN_ROW_PITCH_DP, MAX_ROW_PITCH_DP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_ROW_PITCH_DP, clamped).apply()
        t9PitchPx = context.dp(clamped)
        clampKeyExtra()
        if (showingSymbols) showSymbols() else showMain(showingZh)
    }

    /** Apply a new key size from Settings ("按键大小", 1dp steps): persist,
     *  rebuild the page. Total height never changes — the keys grow and
     *  the gaps shrink by the same total. Clamped to the pitch's maximum
     *  so the visible gap stays >= 2dp. */
    fun setKeyExtraDp(dpValue: Float) {
        val clamped = dpValue.coerceIn(0f, maxKeyExtraDp())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_KEY_EXTRA_DP, clamped).apply()
        keyExtraPx = context.dp(clamped)
        if (showingSymbols) showSymbols() else showMain(showingZh)
    }

    private fun clampKeyExtra() {
        val maxPx = context.dp(maxKeyExtraDp())
        if (keyExtraPx > maxPx) {
            keyExtraPx = maxPx
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_KEY_EXTRA_DP, maxKeyExtraDp()).apply()
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.keyboard_bg))
        // Saved heights win; a fresh install starts at the Doubao default
        // (T9 52dp). Key size starts at 0 (the base size).
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedT9 = prefs.getFloat(KEY_ROW_PITCH_DP, -1f)
        t9PitchPx = if (savedT9 > 0f) context.dp(savedT9) else context.dp(DEFAULT_T9_ROW_PITCH_DP)
        val savedExtra = prefs.getFloat(KEY_KEY_EXTRA_DP, 0f)
        keyExtraPx = context.dp(savedExtra.coerceIn(0f, maxKeyExtraDp()))
        applyT9Padding()
    }

    /** Side padding for the T9 pages: the (effective) key gap plus a small
     *  extra inset, so the key area sits a little in from the screen
     *  edges. It shrinks with the key size, so the keys grow outward
     *  toward the edges too and the keyboard stays centred. */
    private fun applyT9Padding() {
        val side = effGapPx + context.dp(T9_SIDE_INSET_EXTRA_DP)
        setPadding(side, context.dp(2f), side, context.dp(4f))
    }

    /** Show the 9-key page. Chinese and English share this one page: the
     *  only difference is the punctuation next to the 1 key (Chinese comma
     *  in Chinese mode, a full stop in English mode). The 中/英 toggle key
     *  always reads "中/英". */
    fun showMain(zh: Boolean) {
        removeAllViews()
        showingSymbols = false
        showingZh = zh
        applyT9Padding()
        // The 1 key is the punctuation key: ， in Chinese, . in English.
        val sub1 = if (zh) "，" else "."
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
            textKey("中/英", "lang", function = true),
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

    private fun row(vararg keys: TextView, height: Int = t9PitchPx, weights: FloatArray? = null, hGap: Int = effGapPx, vGap: Int = effGapPx) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        for ((i, k) in keys.withIndex()) {
            row.addView(
                k,
                LayoutParams(0, LayoutParams.MATCH_PARENT, weights?.getOrNull(i) ?: 1f).apply {
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
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp ?: mainTextSizeSp)
        // Narrow function labels (e.g. lang / symbol on the bottom row)
        // must stay on one line instead of wrapping into two.
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
