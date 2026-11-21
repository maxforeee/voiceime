package com.voiceime.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.voiceime.Candidate
import com.voiceime.R

/**
 * Scrollable candidate strip with a composing-pinyin label on the left.
 * First candidate is highlighted; tapping any candidate commits it.
 */
class CandidateBar(context: Context) : LinearLayout(context) {

    var onTap: ((Candidate) -> Unit)? = null

    private val composingLabel = TextView(context).apply {
        textSize = 14f
        setTextColor(context.getColor(R.color.key_text_secondary))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(12f), 0, context.dp(8f), 0)
        visibility = View.GONE
    }
    private val row = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(context.getColor(R.color.key_bg))
        addView(composingLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    fun update(composingText: String, candidates: List<Candidate>) {
        composingLabel.text = composingText
        composingLabel.visibility = if (composingText.isEmpty()) View.GONE else View.VISIBLE
        row.removeAllViews()
        candidates.forEachIndexed { i, c ->
            if (i > 0) {
                val divider = View(context).apply { setBackgroundColor(context.getColor(R.color.divider)) }
                row.addView(
                    divider,
                    LayoutParams(context.dp(1f), context.dp(22f)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    }
                )
            }
            val tv = TextView(context).apply {
                text = c.word
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(
                    context.getColor(if (i == 0) R.color.candidate_selected else R.color.candidate_text)
                )
                setPadding(context.dp(14f), 0, context.dp(14f), 0)
                isClickable = true
                setOnClickListener { onTap?.invoke(c) }
            }
            row.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        post { scroll.scrollTo(0, 0) }
    }
}
