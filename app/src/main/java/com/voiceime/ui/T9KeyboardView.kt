package com.voiceime.ui

import android.content.Context
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.voiceime.R

/**
 * T9 grid, built programmatically (no layout inflation, keeps popup fast).
 * Emits raw key codes; all input-state logic lives in VoiceImeService.
 *
 * Main page (5 rows):  1 / 2abc / 3def | 4ghi / 5jkl / 6mno | 7pqrs / 8tuv / 9wxyz
 *                      中/英 / 0 / ⌫ | 符号 / 空格 / 回车
 * Symbol page (6 rows): digits 1-9, ，0。, ？！…, 返回 / 空格 / 回车
 */
class T9KeyboardView(context: Context) : LinearLayout(context) {

    var onKey: ((String) -> Unit)? = null
    var onDeleteLongPress: (() -> Unit)? = null

    // Visible key height. The row pitch adds the vertical gap (top + bottom
    // key margins), so the panel height is rows x (keyHeight + keyGap).
    private var keyHeight: Int
    private var symbolKeyHeight: Int
    private var showingSymbols = false
    private var showingZh = true
    private var showingQwerty = false
    private var qwertyShift = false
    private var qwertyCaps = false
    private var lastShiftTapMs = 0L
    private val gap = context.dp(4f)
    private val keyGap = gap * 2
    private val rowPitch: Int get() = keyHeight + keyGap

    /** Fixed height shared by all panels: rows x (keyHeight + keyGap) plus
     *  vertical padding. The 6 symbol rows fit it because
     *  symbolKeyHeight = rowPitch * 5 / 6. */
    val panelHeightPx: Int
        get() = paddingTop + paddingBottom + 5 * rowPitch

    companion object {
        private const val PREFS = "voiceime_prefs"
        private const val KEY_ROW_PITCH_DP = "row_pitch_dp"
        const val MIN_ROW_PITCH_DP = 52f
        const val MAX_ROW_PITCH_DP = 72f
    }

    /** Current row pitch in dp, for the Settings panel label. */
    fun currentRowPitchDp(): Float =
        rowPitch / resources.displayMetrics.density

    /** Apply a new row pitch from Settings: persist, rebuild the current
     *  page with the new height. Panel height follows the formula, so the
     *  service re-reads panelHeightPx after calling this. */
    fun setRowPitchDp(dpValue: Float) {
        val clamped = dpValue.coerceIn(MIN_ROW_PITCH_DP, MAX_ROW_PITCH_DP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_ROW_PITCH_DP, clamped).apply()
        keyHeight = context.dp(clamped) - keyGap
        symbolKeyHeight = rowPitch * 5 / 6
        if (showingSymbols) showSymbols() else if (showingQwerty) showQwerty() else showMain(showingZh)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.keyboard_bg))
        setPadding(gap, context.dp(2f), gap, context.dp(4f))
        val dm = resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        // Auto height: scale row pitch with the screen, clamped to sane
        // touch sizes; the visible key takes the pitch minus the gap.
        // A height saved in Settings overrides the auto value.
        val target = if (landscape) dm.heightPixels * 0.085f else dm.heightPixels * 0.062f
        val autoPitch = target.toInt().coerceIn(context.dp(42f), context.dp(62f))
        val savedPitch = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_ROW_PITCH_DP, -1f)
        val pitch = if (savedPitch > 0f) context.dp(savedPitch) else autoPitch
        keyHeight = pitch - keyGap
        // 6 symbol rows must never exceed the 5-row main page's total height.
        symbolKeyHeight = rowPitch * 5 / 6
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
            textKey(if (zh) "中/英" else "英/中", "lang", function = true),
            digitKey("0", null),
            textKey("⌫", "del", function = true)
        )
        row(
            textKey("符号", "sym", function = true),
            textKey("空格", "space"),
            textKey("回车", "enter", function = true),
            weights = floatArrayOf(1f, 2f, 1f)
        )
    }

    fun showSymbols() {
        removeAllViews()
        showingSymbols = true
        showingQwerty = false
        row(textKey("1", "1"), textKey("2", "2"), textKey("3", "3"), height = symbolKeyHeight)
        row(textKey("4", "4"), textKey("5", "5"), textKey("6", "6"), height = symbolKeyHeight)
        row(textKey("7", "7"), textKey("8", "8"), textKey("9", "9"), height = symbolKeyHeight)
        row(textKey("，", "，"), textKey("0", "0"), textKey("。", "。"), height = symbolKeyHeight)
        row(textKey("？", "？"), textKey("！", "！"), textKey("…", "…"), height = symbolKeyHeight)
        row(
            textKey("返回", "back", function = true),
            textKey("空格", "space"),
            textKey("回车", "enter", function = true),
            height = symbolKeyHeight,
            weights = floatArrayOf(1f, 2f, 1f)
        )
    }

    /** Full 26-key QWERTY for English (Chinese stays 9-key). Four rows
     *  fill the same panel height: each row is (panel - padding) / 4. */
    fun showQwerty() {
        removeAllViews()
        showingSymbols = false
        showingQwerty = true
        val upper = qwertyShift || qwertyCaps
        val h = (panelHeightPx - paddingTop - paddingBottom) / 4
        fun letter(ch: Char): TextView {
            val s = if (upper) ch.uppercaseChar().toString() else ch.toString()
            return textKey(s, s)
        }
        row(*"qwertyuiop".map { letter(it) }.toTypedArray(), height = h)
        row(
            spacer(),
            *"asdfghjkl".map { letter(it) }.toTypedArray(),
            spacer(),
            height = h,
            weights = floatArrayOf(0.5f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 0.5f)
        )
        row(
            spacer(),
            *"zxcvbnm".map { letter(it) }.toTypedArray(),
            spacer(),
            height = h,
            weights = floatArrayOf(1.5f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1.5f)
        )
        row(
            textKey(if (qwertyCaps) "\u21EA" else "\u21E7", "shift", function = true),
            textKey("\u4E2D/\u82F1", "lang", function = true),
            textKey("\u7B26\u53F7", "sym", function = true),
            textKey("\u7A7A\u683C", "space"),
            textKey("\u232B", "del", function = true),
            textKey("\u56DE\u8F66", "enter", function = true),
            height = h,
            weights = floatArrayOf(1.5f, 1f, 1f, 3.5f, 1.5f, 1.5f)
        )
    }

    /** Shift: one tap = one-shot uppercase, second quick tap = caps lock,
     *  tap while caps is on = back to lowercase. */
    fun toggleQwertyShift() {
        val now = SystemClock.uptimeMillis()
        when {
            qwertyCaps -> { qwertyCaps = false; qwertyShift = false }
            qwertyShift && now - lastShiftTapMs < 400 -> { qwertyShift = false; qwertyCaps = true }
            else -> qwertyShift = true
        }
        lastShiftTapMs = now
        if (showingQwerty) showQwerty()
    }

    /** After a one-shot shifted letter is committed, drop back to lowercase. */
    fun consumeQwertyShiftIfOneShot() {
        if (qwertyShift && !qwertyCaps) {
            qwertyShift = false
            if (showingQwerty) showQwerty()
        }
    }

    /** Invisible spacer: takes width in a row without drawing anything. */
    private fun spacer(): TextView =
        TextView(context).apply { visibility = View.INVISIBLE }

    private fun row(vararg keys: TextView, height: Int = rowPitch, weights: FloatArray? = null) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        for ((i, k) in keys.withIndex()) {
            row.addView(
                k,
                LayoutParams(0, LayoutParams.MATCH_PARENT, weights?.getOrNull(i) ?: 1f).apply {
                    setMargins(gap, gap, gap, gap)
                }
            )
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, height))
    }

    private fun digitKey(digit: String, letters: String?): TextView {
        val label = if (letters == null) {
            SpannableStringBuilder(digit)
        } else {
            SpannableStringBuilder("$digit\n$letters").apply {
                setSpan(RelativeSizeSpan(0.65f), digit.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // Follows the theme (light grey / dark grey) via key_text_secondary.
                setSpan(
                    ForegroundColorSpan(context.getColor(R.color.key_text_secondary)),
                    digit.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return baseKey(label, digit)
    }

    private fun textKey(label: String, code: String, function: Boolean = false): TextView =
        baseKey(label, code, function)

    private fun baseKey(label: CharSequence, code: String, function: Boolean = false): TextView {
        val tv = TextView(context)
        tv.gravity = Gravity.CENTER
        tv.text = label
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        tv.setTextColor(context.getColor(R.color.key_text))
        tv.background = keyBackground(context, function)
        tv.isClickable = true
        tv.isHapticFeedbackEnabled = true
        // Press effect: the key shrinks slightly while held and springs back
        // on release. No popup preview (removed at the user's request).
        // Returning false keeps the TextView's own press/click handling intact.
        var longPressed = false
        tv.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    v.scaleX = 0.95f
                    v.scaleY = 0.95f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.scaleX = 1f
                    v.scaleY = 1f
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
            if (longPressed) longPressed = false else onKey?.invoke(code)
        }
        return tv
    }

}
