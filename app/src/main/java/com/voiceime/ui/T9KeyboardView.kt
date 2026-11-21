package com.voiceime.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
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

    private val keyHeight: Int
    private val gap = context.dp(3f)

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.keyboard_bg))
        setPadding(gap, context.dp(2f), gap, context.dp(4f))
        val dm = resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        // Auto height: scale rows with the screen, clamped to sane touch sizes.
        val target = if (landscape) dm.heightPixels * 0.085f else dm.heightPixels * 0.062f
        keyHeight = target.toInt().coerceIn(context.dp(42f), context.dp(58f))
    }

    fun showMain(zh: Boolean) {
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
            textKey("回车", "enter", function = true)
        )
    }

    fun showSymbols() {
        removeAllViews()
        row(textKey("1", "1"), textKey("2", "2"), textKey("3", "3"))
        row(textKey("4", "4"), textKey("5", "5"), textKey("6", "6"))
        row(textKey("7", "7"), textKey("8", "8"), textKey("9", "9"))
        row(textKey("，", "，"), textKey("0", "0"), textKey("。", "。"))
        row(textKey("？", "？"), textKey("！", "！"), textKey("…", "…"))
        row(
            textKey("返回", "back", function = true),
            textKey("空格", "space"),
            textKey("回车", "enter", function = true)
        )
    }

    private fun row(vararg keys: TextView) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (k in keys) {
            row.addView(
                k,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(gap, gap, gap, gap)
                }
            )
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, keyHeight))
    }

    private fun digitKey(digit: String, letters: String?): TextView {
        val label = if (letters == null) {
            SpannableStringBuilder(digit)
        } else {
            SpannableStringBuilder("$digit\n$letters").apply {
                setSpan(RelativeSizeSpan(0.5f), digit.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        tv.setOnClickListener { onKey?.invoke(code) }
        return tv
    }
}
