package com.voiceime

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.voiceime.asr.AudioCapture
import com.voiceime.asr.DoubaoAsrClient
import com.voiceime.asr.OfflineAsr
import com.voiceime.asr.OfflineModelDownloader
import com.voiceime.asr.SemanticCorrector
import com.voiceime.clipboard.ClipboardHistory
import com.voiceime.lexicon.Lexicon
import com.voiceime.online.SogouSuggestions
import com.voiceime.ui.CandidateBar
import com.voiceime.ui.ClipboardPanelView
import com.voiceime.ui.SettingsPanelView
import com.voiceime.ui.T9KeyboardView
import com.voiceime.ui.VoiceKeyboardView
import androidx.core.content.FileProvider
import com.voiceime.ui.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VoiceIME input method.
 *
 * Two modes, ONE toggle fixed at the top-right corner (the only voice entry):
 *  - KEYBOARD: T9 pinyin grid + scrollable candidate bar, local-first with
 *    debounced online suggestions, 中/英 multitap and 数字/符号 page.
 *  - VOICE: big mic button (hold-to-talk AND tap-toggle), live text in a
 *    popup window above the keyboard, Doubao streaming ASR, final text
 *    semantically corrected by the backend and committed once via
 *    commitText() (original text on backend failure/timeout). The
 *    bottom-right "修改" key shows a coming-soon toast (editing is not
 *    implemented yet).
 *
 * Interrupt safety: switching modes / finishing input stops recording first;
 * no-network / no-permission / recognition failures show a message and never
 * crash or freeze the text field. With an empty build-time key, voice mode
 * shows "not configured" and typing keeps working.
 */
class VoiceImeService : InputMethodService() {

    private enum class Lang { ZH, EN }
    private enum class Mode { KEYBOARD, VOICE }
    private enum class VoiceState { IDLE, RECORDING_HOLD, RECORDING_TAP, PROCESSING, AWAITING_COMMIT }

    private lateinit var candidateBar: CandidateBar
    private lateinit var keyboard: T9KeyboardView
    private lateinit var voiceView: VoiceKeyboardView
    private lateinit var clipboardPanel: ClipboardPanelView
    private lateinit var settingsPanel: SettingsPanelView
    private lateinit var toggleBtn: ImageButton

    private val digits = StringBuilder()
    private var lang = Lang.ZH
    private var symbolsPage = false
    // True while the focused field only takes integers (number/phone).
    private var numericField = false
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
    private val online = SogouSuggestions { d, words -> handler.post { onOnlineResult(d, words) } }
    private var lastLocal = CandidateResult("", emptyList())
    // Index into lastLocal.segmentations; 0 = the reading behind the top word.
    private var segIndex = 0
    private val onlineRequest = Runnable {
        val d = digits.toString()
        if (d.isNotEmpty()) {
            val py = lastLocal.segmentations.getOrNull(segIndex)?.replace(" ", "")
                ?: candidateProvider?.pinyinFor(d) ?: d
            online.request(d, py)
        }
    }

    // Voice session state. sessionId invalidates stale async callbacks.
    private var voiceState = VoiceState.IDLE
    private var sessionId = 0
    private var asrClient: DoubaoAsrClient? = null
    private var capture: AudioCapture? = null
    private var lastInterim = ""
    private var committed = false
    // Offline recognition (Sherpa-ONNX Paraformer int8, downloaded on demand).
    private var offlineMode = false
    private var offlineDownloading = false
    private val offlineChunks = ArrayList<ByteArray>()
    private val processingTimeout = Runnable {
        if (voiceState == VoiceState.PROCESSING) failSession(getString(R.string.voice_timeout))
    }

    // Semantic correction of the ASR final text. While pendingOriginal is
    // set, the correction flow owns the session: late ASR close/error events
    // are ignored and the original text is the fallback that gets committed.
    private val corrector = SemanticCorrector()
    private var pendingOriginal: String? = null
    private val correctionTimeout = Runnable {
        // Correction gave up: the original waits in the popup for the
        // green-arrow tap instead of going on screen by itself.
        val original = pendingOriginal ?: return@Runnable
        pendingOriginal = null
        setPopupText(original)
        pendingCommitText = original
        enterAwaitingCommit()
    }

    // Voice modify mode: the spoken text is an instruction for the backend,
    // never committed to the screen. Only the modified result replaces the
    // field content; on any failure the original stays untouched.
    private var modifyMode = false
    private var modifyPending = false
    // Text already cleaned (or modified) and shown in the popup, waiting for
    // the green-arrow tap to go on screen. Nothing commits by itself.
    private var pendingCommitText: String? = null
    // The current modify round targets the popup text, not the field.
    private var modifyPopupText = false
    private val modifyTimeout = Runnable {
        modifyPending = false
        if (modifyPopupText) {
            // Modify gave up: the pending text stays in the popup, still
            // waiting for the green-arrow tap.
            modifyPopupText = false
            setPopupText(pendingCommitText ?: "")
            enterAwaitingCommit()
        } else {
            finishVoiceSession()
        }
    }

    // Live-text popup: a separate window above the keyboard, so the fixed
    // panel heights are never affected. It owns text display while recording.
    private var voicePopup: PopupWindow? = null
    private var popupText: TextView? = null
    private var popupScroll: ScrollView? = null
    private var popupStatus: TextView? = null
    private val popupDismiss = Runnable { dismissVoicePopup() }

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
        dismissVoicePopup()
        getSystemService(ClipboardManager::class.java)
            ?.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        // Match the system navigation bar to the keyboard background; API 29+
        // would otherwise enforce a contrast scrim against #ECEFF1.
        window?.window?.let { w ->
            w.navigationBarColor = getColor(R.color.keyboard_bg)
            if (Build.VERSION.SDK_INT >= 29) {
                w.isNavigationBarContrastEnforced = false
            }
        }

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
        candidateBar.onSegmentationPick = { idx -> selectSegmentation(idx) }
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
        val settingsBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            background = null
            contentDescription = getString(R.string.settings_desc)
            setOnClickListener { toggleSettingsPanel() }
        }
        topBar.addView(settingsBtn, LinearLayout.LayoutParams(dp(40f), LinearLayout.LayoutParams.MATCH_PARENT))
        topBar.addView(candidateBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        topBar.addView(clipboardBtn, LinearLayout.LayoutParams(dp(36f), LinearLayout.LayoutParams.MATCH_PARENT))
        topBar.addView(toggleBtn, LinearLayout.LayoutParams(dp(40f), LinearLayout.LayoutParams.MATCH_PARENT))
        root.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44f)))

        val divider = View(this).apply { setBackgroundColor(getColor(R.color.divider)) }
        root.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)))

        keyboard = T9KeyboardView(this)
        keyboard.onKey = { k -> onKey(k) }
        keyboard.onDeleteLongPress = { onDeleteLongPress() }
        keyboard.onDigitSwipeUp = { d -> onDigitSwipeUp(d) }
        // Gboard-style fixed height: all three panels share the typing
        // keyboard's formula height, so switching panels never changes the
        // window height; panels scroll internally.
        keyboard.showMain(lang == Lang.ZH)
        val panelHeight = keyboard.panelHeightPx
        root.addView(keyboard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, panelHeight))

        voiceView = VoiceKeyboardView(this).apply {
            visibility = View.GONE
            onHoldStart = { startRecording(hold = true) }
            onHoldEnd = { stopRecording() }
            onTap = {
                when (voiceState) {
                    VoiceState.IDLE -> startRecording(hold = false)
                    VoiceState.RECORDING_TAP -> stopRecording()
                    VoiceState.AWAITING_COMMIT -> commitPending()
                    else -> Unit
                }
            }
            onBackToKeyboard = { switchToKeyboard() }
            onModifyClick = { enterModifyMode() }
        }
        root.addView(voiceView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, panelHeight))

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
        root.addView(clipboardPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, panelHeight))

        settingsPanel = SettingsPanelView(this).apply {
            visibility = View.GONE
            onHeightChange = { dpValue -> applyKeyboardHeight(dpValue) }
            onExportLearned = { exportLearned() }
            onOfflineDownload = { downloadOfflinePack() }
            onClose = { closeSettingsPanel() }
        }
        root.addView(settingsPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, panelHeight))
        settingsPanel.setHeightDp(keyboard.currentRowPitchDp())

        return root
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        stopVoiceSession(salvage = false)
        if (panelOpen) closeClipboardPanel()
        resetInput()
        // Integer-only fields (number/phone) use the same fused 9-key: tap a
        // key and the digit printed on it goes straight in, no swipe needed.
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        numericField = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
        if (mode == Mode.VOICE) {
            resetVoiceUi()
        } else if (::keyboard.isInitialized) {
            if (numericField && lang == Lang.EN) {
                keyboard.showMain(true)
                resizeKeyboardToPage()
            }
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        stopVoiceSession(salvage = false)
        if (panelOpen) closeClipboardPanel()
        resetInput()
        numericField = false
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
            keyboard.showMainForLang(lang == Lang.ZH)
            resizeKeyboardToPage()
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
        modifyMode = false
        modifyPending = false
        modifyPopupText = false
        pendingCommitText = null
        voiceView.readyToCommit = false
        if (BuildConfig.DOUBAO_API_KEY.isEmpty()) {
            voiceView.micEnabled = false
            voiceView.setStatus(getString(R.string.voice_not_configured), isError = true)
        } else {
            voiceView.micEnabled = true
            voiceView.setStatus(getString(R.string.voice_hint_idle))
        }
    }

    /** "修改" entry: the big mic button lights up and records, but what is
     * said is an instruction for the backend, not text to commit. With text
     * waiting in the popup, the instruction modifies the popup text and the
     * result stays in the popup until the green-arrow tap; otherwise it
     * modifies the field text (v1.2.2 behaviour). */
    private fun enterModifyMode() {
        if (voiceState == VoiceState.AWAITING_COMMIT) {
            val pending = pendingCommitText
            if (pending.isNullOrBlank()) return
            if (!networkAvailable()) {
                Toast.makeText(this@VoiceImeService, getString(R.string.voice_edit_offline), Toast.LENGTH_SHORT).show()
                return
            }
            modifyPopupText = true
            modifyMode = true
            voiceView.readyToCommit = false
            voiceState = VoiceState.IDLE
            startRecording(hold = false)
            return
        }
        if (voiceState != VoiceState.IDLE) return
        if (readFieldText().isNullOrBlank()) {
            Toast.makeText(this@VoiceImeService, getString(R.string.voice_edit_empty), Toast.LENGTH_SHORT).show()
            return
        }
        if (!networkAvailable()) {
            Toast.makeText(this@VoiceImeService, getString(R.string.voice_edit_offline), Toast.LENGTH_SHORT).show()
            return
        }
        modifyPopupText = false
        modifyMode = true
        startRecording(hold = false)
    }

    /** Full text of the current input field, or null if the app won't share it. */
    private fun readFieldText(): String? {
        val ic = currentInputConnection ?: return null
        val extracted = try {
            ic.getExtractedText(ExtractedTextRequest(), 0)
        } catch (e: Exception) {
            null
        }
        extracted?.text?.let { return it.toString() }
        val before = ic.getTextBeforeCursor(100000, 0) ?: ""
        val after = ic.getTextAfterCursor(100000, 0) ?: ""
        return before.toString() + after.toString()
    }

    /** Replace the whole field content; false if the app won't allow it. */
    private fun replaceFieldText(newText: String): Boolean {
        val ic = currentInputConnection ?: return false
        val extracted = try {
            ic.getExtractedText(ExtractedTextRequest(), 0)
        } catch (e: Exception) {
            null
        }
        val beforeLen: Int
        val afterLen: Int
        if (extracted?.text != null) {
            beforeLen = extracted.selectionStart.coerceAtLeast(0)
            afterLen = (extracted.text.length - extracted.selectionEnd).coerceAtLeast(0)
        } else {
            beforeLen = ic.getTextBeforeCursor(100000, 0)?.length ?: return false
            afterLen = ic.getTextAfterCursor(100000, 0)?.length ?: 0
        }
        ic.beginBatchEdit()
        val ok = try {
            ic.deleteSurroundingText(beforeLen, afterLen)
            ic.commitText(newText, 1)
            true
        } catch (e: Exception) {
            false
        }
        ic.endBatchEdit()
        return ok
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
            modifyMode = false
            modifyPopupText = false
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
            if (!OfflineAsr.isModelReady(this)) {
                voiceView.setStatus(getString(R.string.voice_offline_no_pack), isError = true)
                modifyMode = false
                modifyPopupText = false
                return
            }
            startOfflineRecording(hold)
            return
        }
        val id = ++sessionId
        committed = false
        lastInterim = ""
        pendingOriginal = null
        voiceState = if (hold) VoiceState.RECORDING_HOLD else VoiceState.RECORDING_TAP
        voiceView.recording = true
        voiceView.holdMode = hold
        voiceView.setInterim("")
        voiceView.setStatus(getString(R.string.voice_connecting))
        showVoicePopup()

        val client = DoubaoAsrClient(BuildConfig.DOUBAO_API_KEY, object : DoubaoAsrClient.Listener {
            override fun onReady() {
                handler.post { if (id == sessionId) onAsrReady(id) }
            }

            override fun onInterim(text: String) {
                handler.post {
                    if (id == sessionId) {
                        lastInterim = text
                        setPopupText(text)
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

    /** No network but the offline pack is present: record locally, decode at stop. */
    private fun startOfflineRecording(hold: Boolean) {
        val id = ++sessionId
        committed = false
        lastInterim = ""
        pendingOriginal = null
        offlineMode = true
        offlineChunks.clear()
        voiceState = if (hold) VoiceState.RECORDING_HOLD else VoiceState.RECORDING_TAP
        voiceView.recording = true
        voiceView.holdMode = hold
        voiceView.setInterim("")
        voiceView.setStatus(getString(R.string.voice_offline_listening))
        showVoicePopup()
        setPopupStatusText(getString(R.string.voice_offline))
        val cap = AudioCapture(
            onChunk = { pcm, n -> offlineChunks.add(pcm.copyOf(n)) },
            onError = { msg -> handler.post { if (id == sessionId) failSession(msg) } }
        )
        capture = cap
        if (!cap.start()) return  // onError already scheduled failSession
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
                if (modifyMode) R.string.voice_edit_hint
                else if (voiceState == VoiceState.RECORDING_HOLD) R.string.voice_listening_hold
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
        setPopupCorrecting(true)
        capture?.stop()
        capture = null
        if (offlineMode) {
            // Offline: decode the captured audio on a worker thread, then
            // commit the raw text (no backend correction without network).
            setPopupStatusText(getString(R.string.voice_processing))
            val id = sessionId
            val chunks = offlineChunks.toList()
            offlineChunks.clear()
            Thread {
                val text = try {
                    OfflineAsr(this).decode(chunks)
                } catch (e: Exception) {
                    ""
                }
                handler.post { if (id == sessionId) onOfflineFinal(text) }
            }.start()
            return
        }
        asrClient?.sendAudio(ByteArray(0), 0, last = true)
        handler.removeCallbacks(processingTimeout)
        handler.postDelayed(processingTimeout, FINAL_TIMEOUT_MS)
    }

    private fun onAsrFinal(text: String) {
        handler.removeCallbacks(processingTimeout)
        voiceState = VoiceState.PROCESSING
        voiceView.recording = false
        voiceView.micEnabled = false
        capture?.stop()
        capture = null
        setPopupText(text)
        setPopupCorrecting(true)
        if (modifyMode) {
            voiceView.setStatus(getString(R.string.voice_editing))
            setPopupStatusText(getString(R.string.voice_editing))
            if (text.isEmpty()) {
                if (modifyPopupText) restorePendingCommit() else finishVoiceSession()
                return
            }
            // Popup-target modify: the original is the text waiting in the
            // popup; field-target modify reads the field (v1.2.2).
            val original = if (modifyPopupText) pendingCommitText else readFieldText()
            if (original.isNullOrBlank()) {
                if (modifyPopupText) restorePendingCommit() else finishVoiceSession()
                return
            }
            val id = sessionId
            modifyPending = true
            handler.removeCallbacks(modifyTimeout)
            handler.postDelayed(modifyTimeout, CORRECTION_TIMEOUT_MS)
            corrector.modify(original, text) { modified ->
                handler.post { if (id == sessionId) onModifyDone(original, modified) }
            }
            return
        }
        voiceView.setStatus(getString(R.string.voice_correcting))
        if (text.isEmpty()) {
            finishVoiceSession()
            return
        }
        // Backend semantic correction (with the Settings cleaning template);
        // on any failure the original text waits in the popup instead. The
        // sessionId guard drops late callbacks after a mode switch / input
        // finish.
        pendingOriginal = text
        val id = sessionId
        handler.removeCallbacks(correctionTimeout)
        handler.postDelayed(correctionTimeout, CORRECTION_TIMEOUT_MS)
        corrector.correct(text, correctLevel()) { corrected ->
            handler.post { if (id == sessionId) onCorrectionDone(text, corrected) }
        }
    }

    /** Offline decode finished: raw text waits in the popup for the
     *  green-arrow tap, no correction. */
    private fun onOfflineFinal(text: String) {
        // Modify needs the backend; offline the instruction is discarded and
        // the original stays untouched.
        if (modifyMode) {
            if (modifyPopupText) {
                modifyPopupText = false
                setPopupText(pendingCommitText ?: "")
                enterAwaitingCommit()
            } else {
                finishVoiceSession()
            }
            return
        }
        voiceState = VoiceState.PROCESSING
        voiceView.recording = false
        voiceView.micEnabled = false
        setPopupText(text)
        setPopupCorrecting(false)
        if (text.isEmpty()) {
            finishVoiceSession()
            return
        }
        pendingCommitText = text
        enterAwaitingCommit()
    }

    /** Correction finished: the cleaned text stays in the popup and waits
     *  for the green-arrow tap; nothing commits by itself. */
    private fun onCorrectionDone(original: String, corrected: String?) {
        handler.removeCallbacks(correctionTimeout)
        val out = corrected ?: original
        setPopupText(out)
        pendingCommitText = out
        enterAwaitingCommit()
    }

    /** Modify result: popup-target modify updates the popup text and waits
     *  for the green-arrow tap; field-target modify replaces the field
     *  (v1.2.2). On any failure the original stays untouched. */
    private fun onModifyDone(original: String, modified: String?) {
        handler.removeCallbacks(modifyTimeout)
        modifyPending = false
        if (modifyPopupText) {
            modifyPopupText = false
            if (modified != null && modified != original) {
                pendingCommitText = modified
                setPopupText(modified)
            } else {
                setPopupText(pendingCommitText ?: original)
            }
            enterAwaitingCommit()
            return
        }
        if (modified != null && modified != original) {
            if (replaceFieldText(modified)) {
                setPopupText(modified)
                committed = true
            }
        }
        finishVoiceSession()
    }

    /** Cleaned / modified / offline text is ready in the popup: tear down
     *  the ASR session, turn the mic green with an up-arrow, and wait for
     *  the tap. The popup stays until the tap (or a mode switch). */
    private fun enterAwaitingCommit() {
        handler.removeCallbacks(correctionTimeout)
        handler.removeCallbacks(modifyTimeout)
        modifyPending = false
        pendingOriginal = null
        cleanupSession()
        voiceState = VoiceState.AWAITING_COMMIT
        voiceView.recording = false
        voiceView.micEnabled = true
        voiceView.readyToCommit = true
        voiceView.setInterim("")
        setPopupCorrecting(false)
        setPopupStatusText(getString(R.string.voice_ready_to_commit))
        voiceView.setStatus(getString(R.string.voice_ready_to_commit))
    }

    /** The pending text could not be modified (empty instruction / no
     *  original): put it back in the popup and keep waiting for the tap. */
    private fun restorePendingCommit() {
        modifyMode = false
        modifyPopupText = false
        setPopupText(pendingCommitText ?: "")
        enterAwaitingCommit()
    }

    /** Green-arrow tap: the previewed text goes on screen, then the popup
     *  goes and the session ends. */
    private fun commitPending() {
        if (voiceState != VoiceState.AWAITING_COMMIT) return
        val text = pendingCommitText
        pendingCommitText = null
        voiceView.readyToCommit = false
        if (!text.isNullOrEmpty()) commitOnce(text)
        dismissVoicePopup()
        finishVoiceSession()
    }

    /** Cleaning template from Settings (normal / medium / strong, default
     *  normal). Only the /correct call sends it; /modify is unaffected. */
    private fun correctLevel(): String {
        val level = getSharedPreferences(SettingsPanelView.PREFS, Context.MODE_PRIVATE)
            .getString(SettingsPanelView.KEY_CORRECT_LEVEL, "normal") ?: "normal"
        return if (level in SettingsPanelView.CORRECT_LEVELS) level else "normal"
    }

    /** Voice session finished: back to idle. */
    private fun finishVoiceSession() {
        handler.removeCallbacks(correctionTimeout)
        handler.removeCallbacks(modifyTimeout)
        modifyPending = false
        pendingOriginal = null
        pendingCommitText = null
        voiceView.readyToCommit = false
        offlineMode = false
        offlineChunks.clear()
        corrector.cancel()
        cleanupSession()
        voiceState = VoiceState.IDLE
        voiceView.recording = false
        voiceView.micEnabled = true
        voiceView.setInterim("")
        setPopupCorrecting(false)
        voiceView.setStatus(
            getString(
                when {
                    modifyMode && committed -> R.string.voice_edit_done
                    committed -> R.string.voice_done
                    else -> R.string.voice_hint_idle
                }
            )
        )
        modifyMode = false
        // Let the final text stay readable for a moment before the popup goes.
        handler.postDelayed(popupDismiss, POPUP_LINGER_MS)
    }

    private fun onAsrClosed() {
        // Correction / modify in flight owns the session; the close is
        // expected after the final packet and must not tear down the pending
        // commit. In modify mode the interim is an instruction, never text
        // to salvage.
        if (pendingOriginal != null || modifyPending) return
        // Text waiting in the popup for the arrow tap: a late close must
        // not tear it down.
        if (voiceState == VoiceState.AWAITING_COMMIT) return
        handler.removeCallbacks(processingTimeout)
        // Connection dropped without a final packet: salvage the last interim.
        if (asrClient?.gotFinal != true && !modifyMode) commitOnce(lastInterim)
        cleanupSession()
        voiceState = VoiceState.IDLE
        voiceView.recording = false
        voiceView.micEnabled = true
        voiceView.setInterim("")
        voiceView.setStatus(
            getString(if (committed) R.string.voice_done else R.string.voice_hint_idle)
        )
        handler.postDelayed(popupDismiss, POPUP_LINGER_MS)
    }

    private fun failSession(message: String) {
        // Final text already received: a late ASR error must not discard it;
        // the correction / modify flow commits the original on its own failure.
        if (pendingOriginal != null || modifyPending) return
        // Text waiting in the popup for the arrow tap: a late ASR error
        // must not dismiss it.
        if (voiceState == VoiceState.AWAITING_COMMIT) return
        handler.removeCallbacks(processingTimeout)
        sessionId++
        capture?.stop()
        capture = null
        asrClient?.cancel()
        asrClient = null
        voiceState = VoiceState.IDLE
        offlineMode = false
        modifyMode = false
        modifyPending = false
        modifyPopupText = false
        pendingCommitText = null
        voiceView.readyToCommit = false
        voiceView.recording = false
        voiceView.micEnabled = true
        // Keep what was heard visible in the popup for a moment; nothing is
        // committed on failure.
        voiceView.setStatus(message, isError = true)
        handler.postDelayed(popupDismiss, POPUP_LINGER_MS)
    }

    /** Mode switch / input finished: stop recording FIRST, then tear down. */
    private fun stopVoiceSession(salvage: Boolean) {
        handler.removeCallbacks(processingTimeout)
        handler.removeCallbacks(correctionTimeout)
        handler.removeCallbacks(modifyTimeout)
        modifyPending = false
        val wasModify = modifyMode
        modifyMode = false
        modifyPopupText = false
        // Awaiting commit: the previewed text is abandoned, never salvaged;
        // the user did not tap the arrow.
        val wasAwaiting = voiceState == VoiceState.AWAITING_COMMIT
        val wasActive = asrClient != null || voiceState != VoiceState.IDLE
        // Correction in flight: the final text beats the last interim, and
        // the late correction callback is dropped by the sessionId bump.
        // In modify mode nothing is salvaged: the interim is an instruction,
        // not text to commit.
        val salvageText = pendingOriginal ?: lastInterim
        pendingOriginal = null
        pendingCommitText = null
        offlineMode = false
        offlineChunks.clear()
        corrector.cancel()
        sessionId++
        capture?.stop()
        capture = null
        if (salvage && wasActive && !committed && !wasModify && !wasAwaiting && salvageText.isNotEmpty()) {
            commitOnce(salvageText)
        }
        asrClient?.cancel()
        asrClient = null
        voiceState = VoiceState.IDLE
        dismissVoicePopup()
        if (::voiceView.isInitialized) {
            voiceView.recording = false
            voiceView.readyToCommit = false
            voiceView.setInterim("")
        }
    }

    private fun cleanupSession() {
        sessionId++
        capture?.stop()
        capture = null
        asrClient?.finish()
        asrClient = null
        modifyMode = false
        modifyPending = false
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

    // ---- live-text popup ------------------------------------------------------

    private fun showVoicePopup() {
        handler.removeCallbacks(popupDismiss)
        val existing = voicePopup
        if (existing != null && existing.isShowing) {
            setPopupText("")
            setPopupCorrecting(false)
            return
        }
        val pad = dp(10f)
        val text = TextView(this).apply {
            textSize = 16f
            setTextColor(getColor(R.color.key_text))
        }
        // The scroll area grows upward with the text, no fixed cap: it
        // keeps growing until it reaches the status bar, then scrolls
        // internally. Covering the app above is fine.
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(popupMaxHeightPx(), MeasureSpec.AT_MOST)
                )
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                text,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            )
        }
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.status_text))
            this.text = getString(R.string.voice_correcting)
            visibility = View.GONE
            setPadding(0, dp(4f), 0, 0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(getColor(R.color.keyboard_bg))
                setStroke(dp(1f), getColor(R.color.divider))
            }
            setPadding(pad, pad, pad, pad)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val anchor = voiceView.rootView
        // The bar stops short of the screen edges: a 12dp margin on each
        // side instead of spanning the full width.
        val margin = dp(12f)
        val hasWidth = anchor.width > 0
        val width = if (hasWidth) anchor.width - 2 * margin else LinearLayout.LayoutParams.MATCH_PARENT
        val popup = PopupWindow(content, width, LinearLayout.LayoutParams.WRAP_CONTENT)
        popup.isFocusable = false
        popup.isOutsideTouchable = false
        voicePopup = popup
        popupText = text
        popupScroll = scroll
        popupStatus = status
        setPopupText("")
        setPopupCorrecting(false)
        // Bottom gravity + the input view's own height puts the popup just
        // above the keyboard, whether the frame is screen- or window-relative.
        popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.START, if (hasWidth) margin else 0, anchor.height + dp(8f))
    }

    private fun setPopupText(text: String) {
        val tv = popupText ?: return
        tv.text = text
        popupScroll?.post { popupScroll?.fullScroll(View.FOCUS_DOWN) }
        // The popup window does not resize itself when its content grows;
        // re-measure it so it keeps growing upward with the text.
        if (voicePopup?.isShowing == true) voicePopup?.update()
    }

    /** Tallest the popup may grow: screen height minus the input view,
     *  minus the status bar, minus the gap and the status line. Short text
     *  stays short (WRAP_CONTENT); long text hits this cap and scrolls. */
    private fun popupMaxHeightPx(): Int {
        val screenH = resources.displayMetrics.heightPixels
        val anchorH = voiceView.rootView.height.takeIf { it > 0 } ?: dp(300f)
        val statusBar = run {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else dp(24f)
        }
        return (screenH - anchorH - statusBar - dp(8f) - dp(20f) - dp(24f))
            .coerceAtLeast(dp(120f))
    }

    private fun setPopupCorrecting(correcting: Boolean) {
        popupStatus?.visibility = if (correcting) View.VISIBLE else View.GONE
    }

    /** Show a one-line status in the popup (e.g. offline mode). */
    private fun setPopupStatusText(text: String) {
        popupStatus?.text = text
        popupStatus?.visibility = View.VISIBLE
    }

    private fun dismissVoicePopup() {
        handler.removeCallbacks(popupDismiss)
        voicePopup?.dismiss()
        voicePopup = null
        popupText = null
        popupScroll = null
        popupStatus = null
    }

    // ---- clipboard panel ------------------------------------------------------

    private fun toggleClipboardPanel() {
        if (panelOpen) closePanels() else openClipboardPanel()
    }

    private fun openClipboardPanel() {
        flushBuffer()
        stopVoiceSession(salvage = true)
        panelOpen = true
        keyboard.visibility = View.GONE
        voiceView.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        clipboardPanel.visibility = View.VISIBLE
        clipboardPanel.render(clipboardHistory.all())
    }

    private fun closeClipboardPanel() {
        closePanels()
    }

    // ---- settings panel -------------------------------------------------------

    private fun toggleSettingsPanel() {
        if (panelOpen) closePanels() else openSettingsPanel()
    }

    private fun openSettingsPanel() {
        flushBuffer()
        stopVoiceSession(salvage = true)
        panelOpen = true
        keyboard.visibility = View.GONE
        voiceView.visibility = View.GONE
        clipboardPanel.visibility = View.GONE
        settingsPanel.visibility = View.VISIBLE
        settingsPanel.setHeightDp(keyboard.currentRowPitchDp())
        refreshOfflineStatus()
    }

    private fun closeSettingsPanel() {
        closePanels()
    }

    /** One overlay panel open at a time; closes whichever is showing and
     *  restores the current mode's view. */
    private fun closePanels() {
        panelOpen = false
        clipboardPanel.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        if (mode == Mode.VOICE) {
            voiceView.visibility = View.VISIBLE
            resetVoiceUi()
        } else {
            keyboard.visibility = View.VISIBLE
            resetInput()
        }
    }

    /** Resize the keyboard view to the height of the page it is showing
     *  (QWERTY and T9 pages share the same height). Call after every
     *  page switch, or the window keeps the old height. */
    private fun resizeKeyboardToPage() {
        if (!::keyboard.isInitialized) return
        keyboard.layoutParams.height = keyboard.currentPanelHeightPx
        keyboard.requestLayout()
    }

    /** Keyboard height tool: rebuild the keyboard at the new pitch, then
     *  resize every panel to the new formula height. */
    private fun applyKeyboardHeight(dpValue: Float) {
        keyboard.setRowPitchDp(dpValue)
        val h = keyboard.panelHeightPx
        keyboard.layoutParams.height = keyboard.currentPanelHeightPx
        voiceView.layoutParams.height = h
        clipboardPanel.layoutParams.height = h
        settingsPanel.layoutParams.height = h
        settingsPanel.setHeightDp(dpValue)
        keyboard.requestLayout()
    }

    /** Export learned words as CSV and open the share sheet. The file lives
     *  in the app's external files dir; sharing goes through FileProvider. */
    private fun exportLearned() {
        val words = candidateProvider?.learnedWords().orEmpty()
        if (words.isEmpty()) {
            Toast.makeText(this, getString(R.string.settings_export_empty), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val file = File(getExternalFilesDir(null), "voiceime-learned-words-$stamp.csv")
            file.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("word,count,last_used\n")
                for (lw in words) {
                    val date = if (lw.lastUsed > 0L) {
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(lw.lastUsed))
                    } else ""
                    w.write("${csvEscape(lw.word)},${lw.count},$date\n")
                }
            }
            val uri = FileProvider.getUriForFile(this, "com.voiceime.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(share, getString(R.string.settings_export_share))
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.settings_export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /** Offline pack row in Settings: current status, refreshed when opened. */
    private fun refreshOfflineStatus() {
        if (!::settingsPanel.isInitialized || offlineDownloading) return
        settingsPanel.setOfflineStatusText(
            getString(
                if (OfflineAsr.isModelReady(this)) R.string.settings_offline_downloaded
                else R.string.settings_offline_not_downloaded
            )
        )
        settingsPanel.setOfflineDownloadEnabled(true)
    }

    /** Download the offline voice pack (GitHub, mirror on failure). */
    private fun downloadOfflinePack() {
        if (offlineDownloading) return
        if (!networkAvailable()) {
            Toast.makeText(this, getString(R.string.voice_no_network), Toast.LENGTH_SHORT).show()
            return
        }
        offlineDownloading = true
        settingsPanel.setOfflineStatusText(getString(R.string.settings_offline_downloading, 0))
        settingsPanel.setOfflineDownloadEnabled(false)
        OfflineModelDownloader(this).download(
            onProgress = { pct ->
                handler.post {
                    settingsPanel.setOfflineStatusText(
                        getString(R.string.settings_offline_downloading, pct)
                    )
                }
            },
            onDone = { ok, errorRes ->
                handler.post {
                    offlineDownloading = false
                    settingsPanel.setOfflineDownloadEnabled(true)
                    if (ok) {
                        settingsPanel.setOfflineStatusText(getString(R.string.settings_offline_downloaded))
                    } else {
                        settingsPanel.setOfflineStatusText(getString(R.string.settings_offline_not_downloaded))
                        Toast.makeText(this, getString(errorRes), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun csvEscape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s

    // ---- key dispatch -------------------------------------------------------

    private fun onKey(key: String) {
        when {
            key == "del" -> onDelete()
            key == "space" -> onSpace()
            key == "enter" -> onEnter()
            key == "lang" -> {
                flushBuffer()
                lang = if (lang == Lang.ZH) Lang.EN else Lang.ZH
                keyboard.showMainForLang(lang == Lang.ZH)
                resizeKeyboardToPage()
            }
            key == "sym" -> {
                flushBuffer()
                symbolsPage = true
                keyboard.showSymbols()
                resizeKeyboardToPage()
            }
            key == "back" -> {
                symbolsPage = false
                keyboard.showMainForLang(lang == Lang.ZH)
                resizeKeyboardToPage()
            }
            symbolsPage -> {
                flushBuffer()
                commit(key)
            }
            numericField && key.length == 1 && key[0] in '0'..'9' -> {
                // Integer field: tap a key, the digit on it goes straight in.
                finalizeTap()
                flushBuffer()
                commit(key)
            }
            key == "shift" -> keyboard.toggleQwertyShift()
            key.length == 1 && (key[0] in 'a'..'z' || key[0] in 'A'..'Z') -> {
                // QWERTY letter: commit directly, no multitap cycling.
                finalizeTap()
                commit(key)
                keyboard.consumeQwertyShiftIfOneShot()
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

    /** Swipe up on a digit key inputs that digit directly, in any field. */
    private fun onDigitSwipeUp(digit: String) {
        finalizeTap()
        flushBuffer()
        commit(digit)
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

    /** Long-press delete: erase the current sentence, back to the previous
     *  period (Chinese 。 or English .). While pinyin is uncommitted, behave
     *  like a normal tap so committed text is never touched by accident. */
    private fun onDeleteLongPress() {
        if (tapLetter != null || digits.isNotEmpty()) {
            onDelete()
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        val idx = before.indexOfLast { it == '。' || it == '.' }
        val count = if (idx < 0) before.length else before.length - idx - 1
        if (count > 0) ic.deleteSurroundingText(count, 0)
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
        segIndex = 0
        val result = if (d.isEmpty()) {
            CandidateResult("", emptyList())
        } else {
            candidateProvider?.lookup(d) ?: CandidateResult(d, emptyList())
        }
        lastLocal = result
        candidateBar.update(result.pinyin.ifEmpty { d }, result.candidates, result.segmentations, segIndex)
        // Local candidates are already on screen; only now arm the network.
        online.cancel()
        handler.removeCallbacks(onlineRequest)
        if (d.isNotEmpty() && lang == Lang.ZH && !symbolsPage) {
            handler.postDelayed(onlineRequest, ONLINE_DEBOUNCE_MS)
        }
    }

    /** Dropdown pick: apply the chosen segmentation and its words, and lock it in. */
    private fun selectSegmentation(index: Int) {
        val segs = lastLocal.segmentations
        val d = digits.toString()
        if (index !in segs.indices || d.isEmpty()) return
        segIndex = index
        val result = candidateProvider?.lookupFor(d, segs[segIndex]) ?: return
        lastLocal = result
        candidateBar.update(result.pinyin.ifEmpty { d }, result.candidates, result.segmentations, segIndex)
        online.cancel()
        handler.removeCallbacks(onlineRequest)
        if (lang == Lang.ZH && !symbolsPage) {
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
        candidateBar.update(lastLocal.pinyin.ifEmpty { d }, merged, lastLocal.segmentations, segIndex)
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
        // A user-chosen segmentation must not be overridden by the global top word.
        val first = if (segIndex > 0) lastLocal.candidates.firstOrNull()
        else candidateProvider?.lookup(d)?.candidates?.firstOrNull()
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
        // Backstop just above the corrector's own 15s call timeout.
        private const val CORRECTION_TIMEOUT_MS = 16000L
        private const val POPUP_LINGER_MS = 1500L
        private val LETTER_GROUPS = mapOf(
            '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
            '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz"
        )
    }
}
