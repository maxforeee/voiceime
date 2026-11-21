package com.voiceime.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import com.voiceime.R

fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

/** Gboard-style light key: rounded white (or light-gray function) rect, darker when pressed. */
fun keyBackground(ctx: Context, function: Boolean): StateListDrawable {
    fun shape(color: Int) = GradientDrawable().apply {
        cornerRadius = ctx.dp(6f).toFloat()
        setColor(color)
    }
    val normalColor =
        if (function) ctx.getColor(R.color.key_function_bg) else ctx.getColor(R.color.key_bg)
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(ctx.getColor(R.color.key_bg_pressed)))
        addState(intArrayOf(), shape(normalColor))
    }
}

/** Flat table-cell background: no rounding, no button frame. The grid row
 *  behind the cells is the divider colour, so the 1px cell margins read
 *  as table lines. Pressed state is a flat colour change. */
fun cellBackground(ctx: Context): StateListDrawable {
    fun shape(color: Int) = GradientDrawable().apply { setColor(color) }
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(ctx.getColor(R.color.key_bg_pressed)))
        addState(intArrayOf(), shape(ctx.getColor(R.color.key_bg)))
    }
}
