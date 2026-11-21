package com.voiceime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import com.voiceime.lexicon.Lexicon
import com.voiceime.ui.CandidateBar
import com.voiceime.ui.T9KeyboardView
import com.voiceime.ui.dp

/**
 * VoiceIME input method.
 * Chunk 2: T9 typing keyboard + scrollable candidate bar + 中/英 + 数字/符号.
 * The top-right toggle is the only voice entry (voice mode lands in Chunk 5).
 */
class VoiceImeService : InputMethodService() {

    private enum class Lang { ZH, EN }

    private lateinit var candidateBar: CandidateBar
    private lateinit var keyboard: T9KeyboardView

    private val digits = StringBuilder()
    private var lang = Lang.ZH
    private var symbolsPage = false

    /** Local lexicon engine, plugged in during Chunk 3. */
    var candidateProvider: CandidateProvider? = null

    override fun onCreate() {
        super.onCreate()
        val lexicon = Lexicon(this)
        lexicon.prepare()
        candidateProvider = lexicon
    }

    // English multitap state (2=abc, 3=def, ... cycling within a timeout).
    private val handler = Handler(Looper.getMainLooper())
    private var tapKey = ' '
    private var tapIndex = 0
    private var tapLetter: String? = null
    private val tapTimeout = Runnable { finalizeTap() }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.keyboard_bg))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(getColor(R.color.key_bg))
        }
        candidateBar = CandidateBar(this)
        candidateBar.onTap = { c -> commitCandidate(c) }
        val toggle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            background = null
            contentDescription = getString(R.string.toggle_desc)
            setOnClickListener { onToggle() }
        }
        topBar.addView(candidateBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        topBar.addView(toggle, LinearLayout.LayoutParams(dp(48f), LinearLayout.LayoutParams.MATCH_PARENT))
        root.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44f)))

        val divider = View(this).apply { setBackgroundColor(getColor(R.color.divider)) }
        root.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)))

        keyboard = T9KeyboardView(this)
        keyboard.onKey = { k -> onKey(k) }
        root.addView(keyboard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        keyboard.showMain(lang == Lang.ZH)
        return root
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        resetInput()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        resetInput()
    }

    private fun resetInput() {
        handler.removeCallbacks(tapTimeout)
        tapLetter = null
        digits.clear()
        symbolsPage = false
        if (::keyboard.isInitialized) {
            keyboard.showMain(lang == Lang.ZH)
            refreshCandidates()
        }
    }

    private fun onToggle() {
        // Voice keyboard is wired in Chunk 5; the toggle position is fixed here.
        Toast.makeText(this, R.string.voice_coming_soon, Toast.LENGTH_SHORT).show()
    }

    // ---- key dispatch -------------------------------------------------------

    private fun onKey(key: String) {
        when {
            key == "del" -> onDelete()
            key == "space" -> onSpace()
            key == "enter" -> onEnter()
            key == "lang" -> {
                flushBuffer()
                lang = if (lang == Lang.ZH) Lang.EN else Lang.ZH
                keyboard.showMain(lang == Lang.ZH)
            }
            key == "sym" -> {
                flushBuffer()
                symbolsPage = true
                keyboard.showSymbols()
            }
            key == "back" -> {
                symbolsPage = false
                keyboard.showMain(lang == Lang.ZH)
            }
            symbolsPage -> {
                flushBuffer()
                commit(key)
            }
            lang == Lang.EN -> onEnglishKey(key)
            key == "1" -> {
                flushBuffer()
                commit("，")
            }
            key == "0" -> {
                flushBuffer()
                commit("0")
            }
            key.length == 1 && key[0] in '2'..'9' -> {
                if (digits.length < MAX_DIGITS) {
                    digits.append(key)
                    refreshCandidates()
                }
            }
        }
    }

    private fun onDelete() {
        if (tapLetter != null) {
            handler.removeCallbacks(tapTimeout)
            tapLetter = null
            currentInputConnection?.setComposingText("", 1)
            return
        }
        if (digits.isNotEmpty()) {
            digits.deleteCharAt(digits.length - 1)
            refreshCandidates()
            return
        }
        currentInputConnection?.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun onSpace() {
        finalizeTap()
        if (digits.isNotEmpty()) {
            commitFirstOrRaw()
        } else {
            commit(" ")
        }
    }

    private fun onEnter() {
        finalizeTap()
        if (digits.isNotEmpty()) {
            commit(digits.toString())
            digits.clear()
            refreshCandidates()
            return
        }
        val ic = currentInputConnection ?: return
        val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    // ---- English multitap ---------------------------------------------------

    private fun onEnglishKey(key: String) {
        if (key.length != 1) return
        val ch = key[0]
        if (ch == '0' || ch == '1') {
            finalizeTap()
            commit(key)
            return
        }
        val letters = LETTER_GROUPS[ch] ?: return
        handler.removeCallbacks(tapTimeout)
        if (tapLetter != null && tapKey == ch) {
            tapIndex = (tapIndex + 1) % letters.length
        } else {
            finalizeTap()
            tapKey = ch
            tapIndex = 0
        }
        val letter = letters[tapIndex].toString()
        tapLetter = letter
        currentInputConnection?.setComposingText(letter, 1)
        handler.postDelayed(tapTimeout, TAP_TIMEOUT_MS)
    }

    private fun finalizeTap() {
        handler.removeCallbacks(tapTimeout)
        val letter = tapLetter ?: return
        tapLetter = null
        currentInputConnection?.commitText(letter, 1)
    }

    // ---- candidates & committing --------------------------------------------

    private fun refreshCandidates() {
        val d = digits.toString()
        val result = if (d.isEmpty()) {
            CandidateResult("", emptyList())
        } else {
            candidateProvider?.lookup(d) ?: CandidateResult(d, emptyList())
        }
        candidateBar.update(result.pinyin.ifEmpty { d }, result.candidates)
    }

    private fun commitCandidate(c: Candidate) {
        candidateProvider?.rememberTap(c.word)
        commit(c.word)
        digits.clear()
        refreshCandidates()
    }

    /** Commit the top candidate if there is one, otherwise the raw digits. */
    private fun commitFirstOrRaw() {
        val d = digits.toString()
        val first = candidateProvider?.lookup(d)?.candidates?.firstOrNull()
        commit(first?.word ?: d)
        digits.clear()
        refreshCandidates()
    }

    private fun flushBuffer() {
        if (digits.isNotEmpty()) commitFirstOrRaw()
    }

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    companion object {
        private const val TAP_TIMEOUT_MS = 900L
        private const val MAX_DIGITS = 20
        private val LETTER_GROUPS = mapOf(
            '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
            '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz"
        )
    }
}
