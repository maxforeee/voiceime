package com.voiceime

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.voiceime.asr.AudioCapture
import com.voiceime.asr.DoubaoAsrClient
import com.voiceime.clipboard.ClipboardHistory
import com.voiceime.lexicon.Lexicon
import com.voiceime.online.GoogleSuggestions
import com.voiceime.ui.CandidateBar
import com.voiceime.ui.ClipboardPanelView
import com.voiceime.ui.T9KeyboardView
import com.voiceime.ui.VoiceKeyboardView
import com.voiceime.ui.dp

/**
 * VoiceIME input method.
 *
 * Two modes, ONE toggle fixed at the top-right corner (the only voice entry):
 *  - KEYBOARD: T9 pinyin grid + scrollable candidate bar, local-first with
 *    debounced online suggestions, 中/英 multitap and 数字/符号 page.
 *  - VOICE: big mic button (hold-to-talk AND tap-toggle), live interim text,
 *    Doubao streaming ASR, final text committed once via commitText().
 *    A disabled "修改" entry is reserved for v2 (not implemented).
 *
 * Interrupt safety: switching modes / finishing input stops recording first;
 * no-network / no-permission / recognition failures show a message and never
 * crash or freeze the text field. With an empty build-time key, voice mode
 * shows "not configured" and typing keeps working.
 */
class VoiceImeService : InputMethodService() {

    private enum class Lang { ZH, EN }
    private enum class Mode { KEYBOARD, VOICE }
    private enum class VoiceState { IDLE, RECORDING_HOLD, RECORDING_TAP, PROCESSING }

    private lateinit var candidateBar: CandidateBar
    private lateinit var keyboard: T9KeyboardView
    private lateinit var voiceView: VoiceKeyboardView
    private lateinit var clipboardPanel: ClipboardPanelView
    private lateinit var toggleBtn: ImageButton

    private val digits = StringBuilder()
    private var lang = Lang.ZH
    private var symbolsPage = false
    private var mode = Mode.KEYBOARD
    private var panelOpen = false

    /** Local lexicon engine (Chunk 3). */
    var candidateProvider: CandidateProvider? = null

    // Clipboard history: last 10 copied items, local only.
    private lateinit var clipboardHistory: ClipboardHistory
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        try {
            val cm = getSystemService(ClipboardManager::class.java) ?: return@OnPrimaryClipChangedListener
            val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this)?.toString()
                if (!text.isNullOrBlank()) clipboardHistory.add(text)
            }
        } catch (e: Exception) {
            // Android 10+ restricts background clipboard reads; skip silently.
        }
    }

    // English multitap state (2=abc, 3=def, ... cycling within a timeout).
    private val handler = Handler(Looper.getMainLooper())
    private var tapKey = ' '
    private var tapIndex = 0
    private var tapLetter: String? = null
    private val tapTimeout = Runnable { finalizeTap() }

    // Online suggestions: debounced, 300ms drop deadline, never blocks typing.
    private val online = GoogleSuggestions { d, words -> handler.post { onOnlineResult(d, words) } }
    private var lastLocal = CandidateResult("", emptyList())
    private val onlineRequest = Runnable {
        val d = digits.toString()
        if (d.isNotEmpty()) online.request(d, candidateProvider?.pinyinFor(d) ?: d)
    }

    // Voice session state. sessionId invalidates stale async callbacks.
    private var voiceState = VoiceState.IDLE
    private var sessionId = 0
    private var asrClient: DoubaoAsrClient? = null
    private var capture: AudioCapture? = null
    private var lastInterim = ""
    private var committed = false
    private val processingTimeout = Runnable {
        if (voiceState == VoiceState.PROCESSING) failSession(getString(R.string.voice_timeout))
    }

    override fun onCreate() {
        super.onCreate()
        val lexicon = Lexicon(this)
        lexicon.prepare()
        candidateProvider = lexicon
        clipboardHistory = ClipboardHistory(this)
        getSystemService(ClipboardManager::class.java)
            ?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        getSystemService(ClipboardManager::class.java)
            ?.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

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
        val clipboardBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_clipboard)
            background = null
            contentDescription = getString(R.string.clipboard_desc)
            setOnClickListener { toggleClipboardPanel() }
        }
        toggleBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            background = null
            contentDescription = getString(R.string.toggle_desc)
            setOnClickListener { onToggle() }
        }
        topBar.addView(candidateBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        topBar.addView(clipboardBtn, LinearLayout.LayoutParams(dp(44f), LinearLayout.LayoutParams.MATCH_PARENT))
        topBar.addView(toggleBtn, LinearLayout.LayoutParams(dp(48f), LinearLayout.LayoutParams.MATCH_PARENT))
        root.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44f)))

        val divider = View(this).apply { setBackgroundColor(getColor(R.color.divider)) }
        root.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)))

        keyboard = T9KeyboardView(this)
        keyboard.onKey = { k -> onKey(k) }
        root.addView(keyboard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        voiceView = VoiceKeyboardView(this).apply {
            visibility = View.GONE
            onHoldStart = { startRecording(hold = true) }
            onHoldEnd = { stopRecording() }
            onTap = {
                when (voiceState) {
                    VoiceState.IDLE -> startRecording(hold = false)
                    VoiceState.RECORDING_TAP -> stopRecording()
                    else -> Unit
                }
            }
            onBackToKeyboard = { switchToKeyboard() }
        }
        root.addView(voiceView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        clipboardPanel = ClipboardPanelView(this).apply {
            visibility = View.GONE
            onPaste = { item ->
                commit(item)
                closeClipboardPanel()
            }
            onClear = {
                clipboardHistory.clear()
                render(clipboardHistory.all())
            }
            onClose = { closeClipboardPanel() }
        }
        root.addView(clipboardPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        keyboard.showMain(lang == Lang.ZH)
        return root
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        stopVoiceSession(salvage = false)
        if (panelOpen) closeClipboardPanel()
        resetInput()
        if (mode == Mode.VOICE) {
            resetVoiceUi()
        } else if (::keyboard.isInitialized) {
            // Number/phone fields open straight onto the digits page.
            val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
            if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) {
                symbolsPage = true
                keyboard.showSymbols()
            }
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        stopVoiceSession(salvage = false)
        if (panelOpen) closeClipboardPanel()
        resetInput()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        stopVoiceSession(salvage = true)
    }

    private fun resetInput() {
        handler.removeCallbacks(tapTimeout)
        handler.removeCallbacks(onlineRequest)
        online.cancel()
        tapLetter = null
        digits.clear()
        symbolsPage = false
        if (::keyboard.isInitialized) {
            keyboard.showMain(lang == Lang.ZH)
            refreshCandidates()
        }
    }

    // ---- mode toggle (top-right, the only voice entry) ----------------------

    private fun onToggle() {
        if (panelOpen) {
            closeClipboardPanel()
            return
        }
        if (mode == Mode.KEYBOARD) switchToVoice() else switchToKeyboard()
    }

    private fun switchToVoice() {
        if (panelOpen) closeClipboardPanel()
        flushBuffer()
        stopVoiceSession(salvage = true)
        mode = Mode.VOICE
        keyboard.visibility = View.GONE
        voiceView.visibility = View.VISIBLE
        toggleBtn.setImageResource(R.drawable.ic_keyboard)
        candidateBar.update("", emptyList())
        resetVoiceUi()
    }

    private fun switchToKeyboard() {
        if (panelOpen) closeClipboardPanel()
        stopVoiceSession(salvage = true)
        mode = Mode.KEYBOARD
        voiceView.visibility = View.GONE
        keyboard.visibility = View.VISIBLE
        toggleBtn.setImageResource(R.drawable.ic_mic)
        resetInput()
    }

    private fun resetVoiceUi() {
        lastInterim = ""
        committed = false
        voiceState = VoiceState.IDLE
        voiceView.setInterim("")
        voiceView.recording = false
        voiceView.holdMode = false
        if (BuildConfig.DOUBAO_API_KEY.isEmpty()) {
            voiceView.micEnabled = false
            voiceView.setStatus(getString(R.string.voice_not_configured), isError = true)
        } else {
            voiceView.micEnabled = true
            voiceView.setStatus(getString(R.string.voice_hint_idle))
        }
    }

    // ---- voice session -------------------------------------------------------

    private fun startRecording(hold: Boolean) {
        if (voiceState != VoiceState.IDLE) return
        if (BuildConfig.DOUBAO_API_KEY.isEmpty()) {
            resetVoiceUi()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            voiceView.setStatus(getString(R.string.voice_need_permission), isError = true)
            try {
                startActivity(
                    Intent(this, PermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // Permission dialog unavailable; typing and retry stay usable.
            }
            return
        }
        if (!networkAvailable()) {
            voiceView.setStatus(getString(R.string.voice_no_network), isError = true)
            return
        }
        val id = ++sessionId
        committed = false
        lastInterim = ""
        voiceState = if (hold) VoiceState.RECORDING_HOLD else VoiceState.RECORDING_TAP
        voiceView.recording = true
        voiceView.holdMode = hold
        voiceView.setInterim("")
        voiceView.setStatus(getString(R.string.voice_connecting))

        val client = DoubaoAsrClient(BuildConfig.DOUBAO_API_KEY, object : DoubaoAsrClient.Listener {
            override fun onReady() {
                handler.post { if (id == sessionId) onAsrReady(id) }
            }

            override fun onInterim(text: String) {
                handler.post {
                    if (id == sessionId) {
                        lastInterim = text
                        voiceView.setInterim(text)
                    }
                }
            }

            override fun onFinal(text: String) {
                handler.post { if (id == sessionId) onAsrFinal(text) }
            }

            override fun onError(message: String) {
                handler.post { if (id == sessionId) failSession(message) }
            }

            override fun onClosed() {
                handler.post { if (id == sessionId) onAsrClosed() }
            }
        })
        asrClient = client
        client.connect()
    }

    private fun onAsrReady(id: Int) {
        if (voiceState != VoiceState.RECORDING_HOLD && voiceState != VoiceState.RECORDING_TAP) return
        val client = asrClient ?: return
        val cap = AudioCapture(
            onChunk = { pcm, n -> client.sendAudio(pcm, n, last = false) },
            onError = { msg -> handler.post { if (id == sessionId) failSession(msg) } }
        )
        capture = cap
        if (!cap.start()) return  // onError already scheduled failSession
        voiceView.setStatus(
            getString(
                if (voiceState == VoiceState.RECORDING_HOLD) R.string.voice_listening_hold
                else R.string.voice_listening_tap
            )
        )
    }

    /** User finished speaking (release / second tap): send the last packet, wait for final. */
    private fun stopRecording() {
        if (voiceState != VoiceState.RECORDING_HOLD && voiceState != VoiceState.RECORDING_TAP) return
        voiceState = VoiceState.PROCESSING
        voiceView.recording = false
        voiceView.micEnabled = false
        voiceView.setStatus(getString(R.string.voice_processing))
        capture?.stop()
        capture = null
        asrClient?.sendAudio(ByteArray(0), 0, last = true)
        handler.removeCallbacks(processingTimeout)
        handler.postDelayed(processingTimeout, FINAL_TIMEOUT_MS)
    }

    private fun onAsrFinal(text: String) {
        handler.removeCallbacks(processingTimeout)
        commitOnce(text)
        cleanupSession()
        voiceState = VoiceState.IDLE
        voiceView.recording = false
        voiceView.micEnabled = true
        voiceView.setInterim("")
        voiceView.setStatus(getString(R.string.voice_done))
    }

    private fun onAsrClosed() {
        handler.removeCallbacks(processingTimeout)
        // Connection dropped without a final packet: salvage the last interim.
        if (asrClient?.gotFinal != true) commitOnce(lastInterim)
        cleanupSession()
        voiceState = VoiceState.IDLE
        voiceView.recording = false
        voiceView.micEnabled = true
        voiceView.setInterim("")
        voiceView.setStatus(
            getString(if (committed) R.string.voice_done else R.string.voice_hint_idle)
        )
    }

    private fun failSession(message: String) {
        handler.removeCallbacks(processingTimeout)
        sessionId++
        capture?.stop()
        capture = null
        asrClient?.cancel()
        asrClient = null
        voiceState = VoiceState.IDLE
        voiceView.recording = false
        voiceView.micEnabled = true
        // Keep the interim text visible so the user sees what was heard;
        // nothing is committed on failure.
        voiceView.setStatus(message, isError = true)
    }

    /** Mode switch / input finished: stop recording FIRST, then tear down. */
    private fun stopVoiceSession(salvage: Boolean) {
        handler.removeCallbacks(processingTimeout)
        val wasActive = asrClient != null || voiceState != VoiceState.IDLE
        sessionId++
        capture?.stop()
        capture = null
        if (salvage && wasActive && !committed && lastInterim.isNotEmpty()) {
            commitOnce(lastInterim)
        }
        asrClient?.cancel()
        asrClient = null
        voiceState = VoiceState.IDLE
        if (::voiceView.isInitialized) {
            voiceView.recording = false
            voiceView.setInterim("")
        }
    }

    private fun cleanupSession() {
        sessionId++
        capture?.stop()
        capture = null
        asrClient?.finish()
        asrClient = null
    }

    /** Final text is committed exactly once; interim text is never committed. */
    private fun commitOnce(text: String) {
        if (committed || text.isEmpty()) return
        committed = true
        currentInputConnection?.commitText(text, 1)
    }

    private fun networkAvailable(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---- clipboard panel ------------------------------------------------------

    private fun toggleClipboardPanel() {
        if (panelOpen) closeClipboardPanel() else openClipboardPanel()
    }

    private fun openClipboardPanel() {
        flushBuffer()
        stopVoiceSession(salvage = true)
        panelOpen = true
        keyboard.visibility = View.GONE
        voiceView.visibility = View.GONE
        clipboardPanel.visibility = View.VISIBLE
        clipboardPanel.render(clipboardHistory.all())
    }

    private fun closeClipboardPanel() {
        panelOpen = false
        clipboardPanel.visibility = View.GONE
        if (mode == Mode.VOICE) {
            voiceView.visibility = View.VISIBLE
            resetVoiceUi()
        } else {
            keyboard.visibility = View.VISIBLE
            resetInput()
        }
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
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
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
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
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
        lastLocal = result
        candidateBar.update(result.pinyin.ifEmpty { d }, result.candidates)
        // Local candidates are already on screen; only now arm the network.
        online.cancel()
        handler.removeCallbacks(onlineRequest)
        if (d.isNotEmpty() && lang == Lang.ZH && !symbolsPage) {
            handler.postDelayed(onlineRequest, ONLINE_DEBOUNCE_MS)
        }
    }

    /** Online results merge in front of local ones, deduped by word. */
    private fun onOnlineResult(d: String, words: List<String>) {
        if (d != digits.toString() || words.isEmpty()) return
        val onlineSet = words.toHashSet()
        val merged = ArrayList<Candidate>(words.size + lastLocal.candidates.size)
        for (w in words) merged.add(Candidate(w))
        for (c in lastLocal.candidates) if (c.word !in onlineSet) merged.add(c)
        candidateBar.update(lastLocal.pinyin.ifEmpty { d }, merged)
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
        private const val ONLINE_DEBOUNCE_MS = 100L
        private const val FINAL_TIMEOUT_MS = 8000L
        private val LETTER_GROUPS = mapOf(
            '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
            '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz"
        )
    }
}
