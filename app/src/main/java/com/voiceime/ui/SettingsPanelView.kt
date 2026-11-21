package com.voiceime.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.voiceime.R

/**
 * Settings panel: same container and height as the clipboard panel, but a
 * tools list instead of clipboard content. First tool: keyboard height.
 */
class SettingsPanelView(context: Context) : LinearLayout(context) {

    var onHeightChange: ((Float) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private var heightDp = 62f
    private val valueLabel = TextView(context).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.key_text))
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.keyboard_bg))

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(context.getColor(R.color.key_bg))
            setPadding(context.dp(14f), context.dp(8f), context.dp(8f), context.dp(8f))
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.settings_title)
            textSize = 15f
            setTextColor(context.getColor(R.color.key_text))
        }
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(actionButton(context.getString(R.string.clipboard_close)) { onClose?.invoke() },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(context.getColor(R.color.key_bg))
            setPadding(context.dp(14f), context.dp(12f), context.dp(14f), context.dp(12f))
        }
        val label = TextView(context).apply {
            text = context.getString(R.string.settings_height)
            textSize = 16f
            setTextColor(context.getColor(R.color.key_text))
        }
        row.addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(actionButton("\u2212") { changeHeight(-2f) },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        row.addView(valueLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(12f)
            marginEnd = context.dp(12f)
        })
        row.addView(actionButton("+") { changeHeight(2f) },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(context.dp(8f), context.dp(8f), context.dp(8f), context.dp(4f))
        })
    }

    fun setHeightDp(dpValue: Float) {
        heightDp = dpValue
        valueLabel.text = "${dpValue.toInt()} dp"
    }

    private fun changeHeight(delta: Float) {
        val next = (heightDp + delta).coerceIn(
            T9KeyboardView.MIN_ROW_PITCH_DP, T9KeyboardView.MAX_ROW_PITCH_DP)
        if (next == heightDp) return
        setHeightDp(next)
        onHeightChange?.invoke(next)
    }

    private fun actionButton(label: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.accent))
            background = keyBackground(context, function = true)
            setPadding(context.dp(16f), context.dp(8f), context.dp(16f), context.dp(8f))
            isClickable = true
            setOnClickListener { action() }
        }
}
