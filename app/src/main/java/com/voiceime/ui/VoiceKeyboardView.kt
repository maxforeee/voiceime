package com.voiceime.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.voiceime.R

/**
 * Big voice keyboard: one large mic button (long-press hold-to-talk AND tap
 * toggle), status above it, and a bottom row [键盘] [修改] [关闭] (v1.3.0,
 * left-aligned). The close button dismisses the voice popup; it is only
 * pressable while the popup is showing and the commit flow has started —
 * otherwise it is greyed out. When the mic is a green arrow, a hint under
 * it says "点一下上屏 · 长按继续说"; long-pressing the arrow continues
 * speaking instead of committing. Live text display is owned by the
 * service's popup window; the embedded interim area stays empty.
 * There is no other voice entry anywhere; the top-right toggle owns switching.
 */
class VoiceKeyboardView(context: Context) : LinearLayout(context) {

    var onHoldStart: (() -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onBackToKeyboard: (() -> Unit)? = null
    var onModifyClick: (() -> Unit)? = null
    var onCloseClick: (() -> Unit)? = null
    /** Long-press the green arrow: keep talking instead of committing. */
    var onArrowLongPress: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var holdActive = false
    private var downPending = false
    private var arrowLongPressed = false

    /** Close button enabled state, driven by the service (follows the
     *  popup): pressable only while the popup is showing and the commit
     *  flow has started; greyed out at all other times. */
    var closeEnabled = false
        set(value) {
            field = value
            closeKey.isEnabled = value
            closeKey.alpha = if (value) 1f else 0.4f
        }

    /** Set by the service; drives touch interpretation and mic appearance. */
    var micEnabled = true
        set(value) {
            field = value
            micButton.alpha = if (value) 1f else 0.4f
        }
    var recording = false
        set(value) {
            field = value
            updateMicAppearance()
        }

    /** Set by the service when cleaned text waits in the popup for the
     *  green-arrow tap: the mic becomes a green up-arrow (commit), not a
     *  record control. Takes precedence over the recording colour. */
    var readyToCommit = false
        set(value) {
            field = value
            updateMicAppearance()
        }
    var holdMode = false

    private val statusText = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.status_text))
        setPadding(context.dp(16f), context.dp(8f), context.dp(16f), 0)
    }
    private val interimText = TextView(context).apply {
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.key_text))
        setPadding(context.dp(20f), context.dp(8f), context.dp(20f), context.dp(8f))
    }
    private val micButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_mic_white)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        contentDescription = context.getString(R.string.mic_desc)
        background = oval(context.getColor(R.color.accent))
    }
    /** Hint under the mic, shown only when it is a green arrow. */
    private val arrowHint = TextView(context).apply {
        text = context.getString(R.string.voice_arrow_hint)
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.status_text))
        visibility = View.GONE
    }
    private val closeKey = TextView(context).apply {
        text = context.getString(R.string.voice_close)
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.key_text))
        background = keyBackground(context, function = true)
        setPadding(context.dp(18f), context.dp(10f), context.dp(18f), context.dp(10f))
        isClickable = true
        isEnabled = false
        alpha = 0.4f
        setOnClickListener { onCloseClick?.invoke() }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(context.getColor(R.color.keyboard_bg))

        addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(interimText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val micSize = context.dp(132f)
        addView(micButton, LayoutParams(micSize, micSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = context.dp(8f)
            bottomMargin = context.dp(4f)
        })
        addView(arrowHint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(4f)
        })

        // Bottom row (v1.3.0): [键盘] [修改] [关闭], left-aligned. 修改 is
        // the first button next to the keyboard, 关闭 the second next to it.
        val bottomRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backKey = TextView(context).apply {
            text = context.getString(R.string.voice_back_keyboard)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.key_text))
            background = keyBackground(context, function = true)
            setPadding(context.dp(18f), context.dp(10f), context.dp(18f), context.dp(10f))
            isClickable = true
            setOnClickListener { onBackToKeyboard?.invoke() }
        }
        val editKey = TextView(context).apply {
            text = context.getString(R.string.voice_edit)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.key_text))
            background = keyBackground(context, function = true)
            setPadding(context.dp(18f), context.dp(10f), context.dp(18f), context.dp(10f))
            isClickable = true
            setOnClickListener { onModifyClick?.invoke() }
        }
        bottomRow.addView(backKey, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        bottomRow.addView(editKey, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(12f)
        })
        bottomRow.addView(closeKey, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(12f)
        })
        bottomRow.addView(View(context), LayoutParams(0, 1, 1f))
        addView(bottomRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(context.dp(12f), 0, context.dp(12f), context.dp(10f))
        })

        micButton.setOnTouchListener { _, event -> handleMicTouch(event) }
    }

    fun setStatus(text: String, isError: Boolean = false) {
        statusText.text = text
        statusText.setTextColor(
            context.getColor(if (isError) R.color.error_text else R.color.status_text)
        )
    }

    fun setInterim(text: String) {
        interimText.text = text
    }

    private fun updateMicAppearance() {
        val color = when {
            readyToCommit -> context.getColor(R.color.voice_commit)
            recording -> context.getColor(R.color.voice_listening)
            else -> context.getColor(R.color.accent)
        }
        micButton.background = oval(color)
        micButton.setImageResource(
            if (readyToCommit) R.drawable.ic_arrow_up else R.drawable.ic_mic_white
        )
        micButton.contentDescription =
            if (readyToCommit) context.getString(R.string.voice_commit_desc)
            else context.getString(R.string.mic_desc)
        // Hint under the arrow, so people know a long-press continues speaking.
        arrowHint.visibility = if (readyToCommit) View.VISIBLE else View.GONE
        // The glyph sits on the oval: white-on-blue in light mode,
        // dark-on-light-blue in dark mode (white on #8AB4F8 is too weak).
        micButton.imageTintList =
            ColorStateList.valueOf(context.getColor(R.color.on_accent))
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun handleMicTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!micEnabled) return true
                // While tap-recording, the UP of this press is the stop tap.
                if (recording) return true
                if (readyToCommit) {
                    // Green arrow: tap commits, long-press keeps talking.
                    // Arm the long-press; the UP decides which one it was.
                    arrowLongPressed = false
                    handler.postDelayed({
                        arrowLongPressed = true
                        onArrowLongPress?.invoke()
                    }, LONG_PRESS_MS)
                    return true
                }
                downPending = true
                handler.postDelayed({
                    if (downPending) {
                        downPending = false
                        holdActive = true
                        onHoldStart?.invoke()
                    }
                }, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacksAndMessages(null)
                val wasPending = downPending
                downPending = false
                if (readyToCommit) {
                    // Green arrow: a long-press already continued speaking;
                    // a short tap commits the previewed text.
                    if (!arrowLongPressed) onTap?.invoke()
                    arrowLongPressed = false
                    return true
                }
                if (holdActive) {
                    holdActive = false
                    onHoldEnd?.invoke()
                } else if (wasPending || recording) {
                    // Short tap: start when idle, stop when tap-recording.
                    onTap?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacksAndMessages(null)
                downPending = false
                arrowLongPressed = false
                if (holdActive) {
                    holdActive = false
                    onHoldEnd?.invoke()
                }
                return true
            }
        }
        return false
    }

    companion object {
        private const val LONG_PRESS_MS = 350L
    }
}
