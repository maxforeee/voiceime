package com.voiceime.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
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

    // Visible key height. The row pitch adds the vertical gap (top + bottom
    // key margins), so the panel height is rows x (keyHeight + keyGap).
    private val keyHeight: Int
    private val symbolKeyHeight: Int
    private val gap = context.dp(4f)
    private val keyGap = gap * 2
    private val rowPitch: Int get() = keyHeight + keyGap
    private var keyPreview: PopupWindow? = null

    /** Fixed height shared by all panels: rows x (keyHeight + keyGap) plus
     *  vertical padding. The 6 symbol rows fit it because
     *  symbolKeyHeight = rowPitch * 5 / 6. */
    val panelHeightPx: Int
        get() = paddingTop + paddingBottom + 5 * rowPitch

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.keyboard_bg))
        setPadding(gap, context.dp(2f), gap, context.dp(4f))
        val dm = resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        // Auto height: scale row pitch with the screen, clamped to sane
        // touch sizes; the visible key takes the pitch minus the gap.
        val target = if (landscape) dm.heightPixels * 0.085f else dm.heightPixels * 0.062f
        keyHeight = target.toInt().coerceIn(context.dp(42f), context.dp(62f)) - keyGap
        // 6 symbol rows must never exceed the 5-row main page's total height.
        symbolKeyHeight = rowPitch * 5 / 6
    }

    fun showMain(zh: Boolean) {
        dismissKeyPreview()
        removeAllViews()
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
        dismissKeyPreview()
        removeAllViews()
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
                // Darker than key_text_secondary so the small letters stay readable.
                setSpan(
                    ForegroundColorSpan(0xFF5F6368.toInt()),
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
        // Returning false keeps the TextView's own press/click handling intact.
        tv.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    showKeyPreview(v as TextView)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dismissKeyPreview()
            }
            false
        }
        tv.setOnClickListener { onKey?.invoke(code) }
        return tv
    }

    /** Enlarged copy of the pressed key, floating just above it (Gboard-style). */
    private fun showKeyPreview(key: TextView) {
        dismissKeyPreview()
        val content = TextView(context).apply {
            gravity = Gravity.CENTER
            text = key.text.toString().substringBefore('\n')
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(context.getColor(R.color.key_text))
            background = keyBackground(context, false)
            minWidth = key.width
            val padH = context.dp(8f)
            val padV = context.dp(10f)
            setPadding(padH, padV, padH, padV)
        }
        val popup = PopupWindow(content, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        // Let previews on the top row extend above the keyboard window.
        popup.isClippingEnabled = false
        popup.isTouchable = false
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val loc = IntArray(2)
        key.getLocationInWindow(loc)
        val x = loc[0] + (key.width - content.measuredWidth) / 2
        val y = loc[1] - content.measuredHeight
        popup.showAtLocation(key, Gravity.NO_GRAVITY, x, y)
        keyPreview = popup
    }

    private fun dismissKeyPreview() {
        keyPreview?.dismiss()
        keyPreview = null
    }
}
