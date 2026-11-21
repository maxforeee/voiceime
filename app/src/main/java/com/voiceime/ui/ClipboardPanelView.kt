package com.voiceime.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.voiceime.R

/**
 * Clipboard panel: last 10 copied items, tap to paste, plus clear and close.
 * The only extra feature in v1 besides typing and voice.
 */
class ClipboardPanelView(context: Context) : LinearLayout(context) {

    var onPaste: ((String) -> Unit)? = null
    var onClear: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val emptyLabel = TextView(context).apply {
        text = context.getString(R.string.clipboard_empty)
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.key_text_secondary))
        setPadding(context.dp(16f), context.dp(32f), context.dp(16f), context.dp(32f))
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
            text = context.getString(R.string.clipboard_title)
            textSize = 15f
            setTextColor(context.getColor(R.color.key_text))
        }
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(actionButton(context.getString(R.string.clipboard_clear)) { onClear?.invoke() },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        header.addView(actionButton(context.getString(R.string.clipboard_close)) { onClose?.invoke() },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(8f)
            })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        // The service gives this panel a fixed height, so weight 1 resolves to
        // the space below the header and the list scrolls instead of collapsing.
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun render(items: List<String>) {
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(emptyLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        for (item in items) {
            val tv = TextView(context).apply {
                text = item
                textSize = 16f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(context.getColor(R.color.key_text))
                setBackgroundColor(context.getColor(R.color.key_bg))
                setPadding(context.dp(14f), context.dp(12f), context.dp(14f), context.dp(12f))
                isClickable = true
                setOnClickListener { onPaste?.invoke(item) }
            }
            list.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(context.dp(8f), context.dp(4f), context.dp(8f), context.dp(4f))
            })
        }
    }

    private fun actionButton(label: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.accent))
            background = keyBackground(context, function = true)
            setPadding(context.dp(14f), context.dp(8f), context.dp(14f), context.dp(8f))
            isClickable = true
            setOnClickListener { action() }
        }
}
