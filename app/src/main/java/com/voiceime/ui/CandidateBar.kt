package com.voiceime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.voiceime.Candidate
import com.voiceime.R
import com.voiceime.lexicon.EnglishLexicon

/**
 * Scrollable candidate strip with a composing-pinyin label on the left.
 * First candidate is highlighted; tapping any candidate commits it.
 * When several pinyin segmentations exist, the label shows a ▾; tapping it
 * drops down a list of segmentations, one per row — tapping a row selects
 * that segmentation and locks it in (no cycling).
 */
class CandidateBar(context: Context) : LinearLayout(context) {

    var onTap: ((Candidate) -> Unit)? = null

    /** A dropdown row was picked: apply that segmentation index. */
    var onSegmentationPick: ((Int) -> Unit)? = null

    /** An English letter chip was tapped: lock that letter. */
    var onEnglishLetterPick: ((Char) -> Unit)? = null

    private var segmentations: List<String> = emptyList()
    private var selectedSegmentation = 0
    private var segPopup: PopupWindow? = null

    private var enPopup: PopupWindow? = null
    private var enResult: EnglishLexicon.EnglishResult? = null
    private var enLockedPrefix: String = ""
    private var enCorrected: Boolean = false
    private var aiCandidate: Candidate? = null

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

    fun update(
        composingText: String,
        candidates: List<Candidate>,
        segmentations: List<String> = emptyList(),
        selectedSegmentation: Int = 0
    ) {
        dismissEnglishDropdown()
        aiCandidate = null
        this.segmentations = segmentations
        this.selectedSegmentation = selectedSegmentation
        val canPick = segmentations.size > 1
        composingLabel.text = if (canPick) "$composingText ▾" else composingText
        composingLabel.isClickable = canPick
        composingLabel.setOnClickListener(
            if (canPick) View.OnClickListener { showSegmentationDropdown() } else null
        )
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

    /** Dropdown below the pinyin label: one segmentation per row. */
    private fun showSegmentationDropdown() {
        dismissSegmentationDropdown()
        if (segmentations.size < 2) return
        val list = LinearLayout(context).apply { orientation = VERTICAL }
        segmentations.forEachIndexed { i, seg ->
            val tv = TextView(context).apply {
                text = seg
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(
                    context.getColor(
                        if (i == selectedSegmentation) R.color.candidate_selected
                        else R.color.candidate_text
                    )
                )
                background = keyBackground(context, false)
                setPadding(context.dp(16f), context.dp(10f), context.dp(16f), context.dp(10f))
                isClickable = true
                setOnClickListener {
                    dismissSegmentationDropdown()
                    onSegmentationPick?.invoke(i)
                }
            }
            list.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val dropdownScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val popup = PopupWindow(dropdownScroll, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true)
        // A background is required for outside taps to dismiss the popup.
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        dropdownScroll.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxH = context.dp(240f)
        if (dropdownScroll.measuredHeight > maxH) {
            popup.height = maxH
            popup.width = dropdownScroll.measuredWidth
        }
        popup.showAsDropDown(composingLabel)
        segPopup = popup
    }

    private fun dismissSegmentationDropdown() {
        segPopup?.dismiss()
        segPopup = null
    }

    // ---- English 9-key (v1.3.0) --------------------------------------------

    /**
     * English candidates: the main strip shows common words first, then
     * uncommon words; the dropdown (auto-shown on each keypress, same
     * PopupWindow style as the segmentation dropdown) shows the letter
     * chips for the current position on top — tap one to lock it — then
     * the word rows, with a divider row before the uncommon words.
     * Pass null (or an empty result) to clear the strip and dismiss the
     * dropdown (mode switched, input cleared, word committed).
     */
    fun updateEnglish(
        result: EnglishLexicon.EnglishResult?,
        lockedPrefix: String = "",
        corrected: Boolean = false
    ) {
        enResult = result
        enLockedPrefix = lockedPrefix
        enCorrected = corrected
        dismissSegmentationDropdown()
        aiCandidate = null
        if (result == null || (result.letters.isEmpty() && result.common.isEmpty() && result.rare.isEmpty())) {
            composingLabel.visibility = View.GONE
            row.removeAllViews()
            dismissEnglishDropdown()
            return
        }
        composingLabel.text = if (lockedPrefix.isEmpty()) "▾" else "$lockedPrefix ▾"
        composingLabel.isClickable = true
        composingLabel.setOnClickListener { showEnglishDropdown() }
        composingLabel.visibility = View.VISIBLE
        row.removeAllViews()
        var first = true
        for (w in result.common) {
            addWordCell(Candidate(w), first)
            first = false
        }
        for (w in result.rare) {
            addWordCell(Candidate(w), first)
            first = false
        }
        post { scroll.scrollTo(0, 0) }
        showEnglishDropdown()
    }

    private fun addWordCell(c: Candidate, highlight: Boolean) {
        if (row.childCount > 0) {
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
                context.getColor(if (highlight) R.color.candidate_selected else R.color.candidate_text)
            )
            setPadding(context.dp(14f), 0, context.dp(14f), 0)
            isClickable = true
            setOnClickListener { onTap?.invoke(c) }
        }
        row.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    /** English dropdown: letter chips + word rows + divider before rare. */
    private fun showEnglishDropdown() {
        val result = enResult ?: return
        if (result.letters.isEmpty() && result.common.isEmpty() && result.rare.isEmpty()) return
        val list = LinearLayout(context).apply { orientation = VERTICAL }
        // Letter chips for the current position, side by side; the locked
        // letter is highlighted. Tap one to lock it.
        if (result.letters.isNotEmpty()) {
            val chips = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(8f), context.dp(10f), context.dp(8f), context.dp(6f))
            }
            for (ch in result.letters) {
                val selected = result.selectedLetter == ch
                val tv = TextView(context).apply {
                    text = ch.uppercaseChar().toString()
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(
                        context.getColor(if (selected) R.color.candidate_selected else R.color.candidate_text)
                    )
                    background = if (selected) keyBackground(context, false) else null
                    setPadding(context.dp(16f), context.dp(8f), context.dp(16f), context.dp(8f))
                    isClickable = true
                    setOnClickListener {
                        onEnglishLetterPick?.invoke(ch)
                    }
                }
                chips.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }
            list.addView(chips, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        // Local correction note (adjacent-key fix, high confidence only).
        if (enCorrected) {
            val note = TextView(context).apply {
                text = context.getString(R.string.candidate_corrected)
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(context.getColor(R.color.key_text_secondary))
                setPadding(context.dp(16f), context.dp(4f), context.dp(16f), context.dp(4f))
            }
            list.addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        // Word rows: common first, divider, then uncommon.
        for (w in result.common) {
            list.addView(englishWordRow(w, highlight = w == result.common.firstOrNull()),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        if (result.rare.isNotEmpty() && result.common.isNotEmpty()) {
            val divider = TextView(context).apply {
                text = context.getString(R.string.candidate_rare_divider)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.key_text_secondary))
                setPadding(context.dp(16f), context.dp(6f), context.dp(16f), context.dp(6f))
            }
            list.addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        for (w in result.rare) {
            list.addView(englishWordRow(w, highlight = false),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val dropdownScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        dismissEnglishDropdown()
        // Not focusable: the IME keeps input focus and typing is never
        // interrupted; tapping a row still works, tapping outside dismisses.
        val popup = PopupWindow(dropdownScroll, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, false)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        dropdownScroll.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxH = context.dp(240f)
        if (dropdownScroll.measuredHeight > maxH) {
            popup.height = maxH
            popup.width = dropdownScroll.measuredWidth
        }
        popup.showAsDropDown(composingLabel)
        enPopup = popup
    }

    private fun englishWordRow(word: String, highlight: Boolean): TextView =
        TextView(context).apply {
            text = word
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(
                context.getColor(if (highlight) R.color.candidate_selected else R.color.candidate_text)
            )
            background = keyBackground(context, false)
            setPadding(context.dp(16f), context.dp(10f), context.dp(16f), context.dp(10f))
            isClickable = true
            setOnClickListener {
                dismissEnglishDropdown()
                onTap?.invoke(Candidate(word))
            }
        }

    fun dismissEnglishDropdown() {
        enPopup?.dismiss()
        enPopup = null
    }

    // ---- AI suggestion (v1.3.0) --------------------------------------------

    /**
     * Append an AI suggestion to the current strip, marked with an "AI" tag
     * and in the accent colour. It never commits by itself — tapping it
     * applies it (the service routes AI candidates separately). The next
     * update()/updateEnglish() rebuilds the strip and clears it.
     */
    fun addAiCandidate(c: Candidate) {
        if (aiCandidate?.word == c.word) return
        aiCandidate = c
        if (row.childCount > 0) {
            val divider = View(context).apply { setBackgroundColor(context.getColor(R.color.divider)) }
            row.addView(
                divider,
                LayoutParams(context.dp(1f), context.dp(22f)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
            )
        }
        val tv = TextView(context).apply {
            text = "AI ${c.word}"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.accent))
            setPadding(context.dp(14f), 0, context.dp(14f), 0)
            isClickable = true
            setOnClickListener { onTap?.invoke(c) }
        }
        row.addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
}
