package com.voiceime.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.voiceime.R

/**
 * Settings panel: same container and height as the clipboard panel, but a
 * tools list instead of clipboard content. Tools: keyboard height,
 * cleaning template (voice-input correction only, not modify), export,
 * offline voice pack.
 */
class SettingsPanelView(context: Context) : LinearLayout(context) {

    var onHeightChange: ((Float) -> Unit)? = null
    var onExportLearned: (() -> Unit)? = null
    var onOfflineDownload: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    companion object {
        const val PREFS = voiceime_prefs
        const val KEY_CORRECT_LEVEL = correct_level
        val CORRECT_LEVELS = listOf(normal, medium, strong)
    }

    private var heightDp = 62f

    private var correctLevel: String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CORRECT_LEVEL, normal) ?: normal
    private val levelButtons = mutableMapOf<String, TextView>()
    private val valueLabel = TextView(context).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.key_text))
    }
    private val offlineStatusLabel = TextView(context).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.key_text_secondary))
    }
    private val offlineDownloadBtn =
        actionButton(context.getString(R.string.settings_offline_download_btn)) {
            onOfflineDownload?.invoke()
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

        // Tool rows live inside a ScrollView: on phones with a larger font
        // scale the rows can exceed the fixed panel height, and the bottom
        // row (offline pack) would otherwise be clipped with no way to
        // reach it. The header stays fixed at the top.
        val body = LinearLayout(context).apply { orientation = VERTICAL }

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
        body.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(context.dp(8f), context.dp(8f), context.dp(8f), context.dp(4f))
        })

        // Cleaning template: three levels, only the voice-input /correct
        // call reads it; /modify is unaffected. Saved to SharedPreferences;
        // the service reads it fresh on every correction.
        val levelRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(context.getColor(R.color.key_bg))
            setPadding(context.dp(14f), context.dp(12f), context.dp(14f), context.dp(12f))
        }
        val levelLabel = TextView(context).apply {
            text = context.getString(R.string.settings_level)
            textSize = 16f
            setTextColor(context.getColor(R.color.key_text))
        }
        levelRow.addView(levelLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val levelNames = mapOf(
            normal to R.string.settings_level_normal,
            medium to R.string.settings_level_medium,
            strong to R.string.settings_level_strong
        )
        for ((level, nameRes) in levelNames) {
            val btn = levelButton(context.getString(nameRes)) { setCorrectLevel(level) }
            levelButtons[level] = btn
            levelRow.addView(btn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(6f)
            })
        }
        body.addView(levelRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(context.dp(8f), context.dp(4f), context.dp(8f), context.dp(4f))
        })
        refreshLevelButtons()

        val exportRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(context.getColor(R.color.key_bg))
            setPadding(context.dp(14f), context.dp(12f), context.dp(14f), context.dp(12f))
        }
        val exportLabel = TextView(context).apply {
            text = context.getString(R.string.settings_export)
            textSize = 16f
            setTextColor(context.getColor(R.color.key_text))
        }
        exportRow.addView(exportLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        exportRow.addView(actionButton(context.getString(R.string.settings_export_btn)) { onExportLearned?.invoke() },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        body.addView(exportRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(context.dp(8f), context.dp(4f), context.dp(8f), context.dp(4f))
        })

        val offlineRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(context.getColor(R.color.key_bg))
            setPadding(context.dp(14f), context.dp(12f), context.dp(14f), context.dp(12f))
        }
        val offlineLabel = TextView(context).apply {
            text = context.getString(R.string.settings_offline_pack)
            textSize = 16f
            setTextColor(context.getColor(R.color.key_text))
        }
        offlineRow.addView(offlineLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        offlineRow.addView(offlineStatusLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = context.dp(12f)
        })
        offlineRow.addView(offlineDownloadBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        body.addView(offlineRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(context.dp(8f), context.dp(4f), context.dp(8f), context.dp(4f))
        })
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setHeightDp(dpValue: Float) {
        heightDp = dpValue
        valueLabel.text = "${dpValue.toInt()} dp"
    }

    fun setOfflineStatusText(text: String) {
        offlineStatusLabel.text = text
    }

    fun setOfflineDownloadEnabled(enabled: Boolean) {
        offlineDownloadBtn.isEnabled = enabled
        offlineDownloadBtn.alpha = if (enabled) 1f else 0.4f
    }

    /** Selected level: accent text on the function background; the others
     *  are plain secondary text with no background. */
    private fun levelButton(label: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.key_text_secondary))
            setPadding(context.dp(14f), context.dp(8f), context.dp(14f), context.dp(8f))
            isClickable = true
            setOnClickListener { action() }
        }

    private fun refreshLevelButtons() {
        for ((level, btn) in levelButtons) {
            if (level == correctLevel) {
                btn.setTextColor(context.getColor(R.color.accent))
                btn.background = keyBackground(context, function = true)
            } else {
                btn.setTextColor(context.getColor(R.color.key_text_secondary))
                btn.background = null
            }
        }
    }

    fun setCorrectLevel(level: String) {
        val next = if (level in CORRECT_LEVELS) level else normal
        if (next == correctLevel) return
        correctLevel = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CORRECT_LEVEL, next).apply()
        refreshLevelButtons()
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
