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
import com.voiceime.lexicon.CloudLexicon
import com.voiceime.lexicon.EnglishLexicon
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
 * VoiceIME input method (v1.3.0: one 9-key grid, no 26-key QWERTY).
 *
 * Two modes, ONE toggle fixed at the top-right corner (the only voice entry):
 *  - KEYBOARD: one 9-key grid for Chinese (pinyin) and English (step-by-step
 *    letter picking: press a key, pick a letter in the dropdown, press the
 *    next key, pick a word), scrollable candidate bar, local-first with
 *    debounced online suggestions, and a 数字/符号 page. The 1 key is a
 *    punctuation key: ， in Chinese mode, . in English mode; digits come
 *    from swiping up on a key (tap in integer fields).
 *  - VOICE: big mic button (hold-to-talk AND tap-toggle), live text in a
 *    popup window above the keyboard, Doubao streaming ASR, final text
 *    semantically corrected by the backend. The popup stays until the user
 *    taps the close button in the bottom row [键盘] [修改] [关闭]; the
 *    green arrow commits the text (a long-press on it continues speaking),
 *    and 修改 edits the popup text in place (a small window shows the
 *    recognized instruction).
 *
 * Typing correction (v1.3.0): local first (legal-syllable segmentation,
 * adjacent-key / single-edit correction, frequency ranking), then the AI
 * typo check (/typo) as judge — only on a pause, only for suspicious
 * input, results shown as AI-marked candidates, never forced.
 *
 * Interrupt safety: switching modes / finishing input stops recording first;
 * no-network / no-permission / recognition failures show a message and never
 * crash or freeze the text field. With an empty build-time key, voice mode
 * shows "not configured" and typing keeps working.
 */
class VoiceImeService : InputMethodService() {

    private enum class Lang { ZH, EN }
    private enum class Mode { KEYBOARD, VOICE }
    private enum class VoiceState { IDLE, RECORDING_HOLD, RECORDING_TAP, PROCESSING, AWAITING_COMMIT, COMMITTED }

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

    private val handler = Handler(Looper.getMainLooper())
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // English step-by-step state (v1.3.0): pressed keys + one locked letter
    // per position. A locked letter is a choice the user tapped in the
    // dropdown; deleting it unlocks that position again.
    private val enKeys = StringBuilder()
    private val enLocked = ArrayList<Char?>()
    private var enResult: EnglishLexicon.EnglishResult? = null
    @Volatile
    private var englishLexicon: EnglishLexicon? = null
    @Volatile
    private var cloudLexicon: CloudLexicon? = null
    // The segment committed by the last green-arrow tap (for replace-in-
    // place when the user modifies it afterwards). -1 = nothing committed.
    private var committedStart = -1
    private var committedText = ""
    // Text in the field before the popup's segment was appended (append
    // mode): the popup text goes after this, never interleaved.
    private var popupBaseText = ""
    // AI typo check (v1.3.0): local first, AI as the judge. Debounced
    // (500ms pause), one call per suspicion, never blocks typing; the AI
    // result appears as a candidate, never a forced replacement.
    private val typoCheck = Runnable { runTypoCheck() }
    private var lastTypoSentence = ""
    private var typoInFlight = false
    // Generation: bumped on every new schedule; a late AI callback whose
    // generation no longer matches is discarded (the user kept typing).
    private var typoGen = 0
    // Pending input snapshot at schedule time; the AI result is only shown
    // if the input is unchanged when it comes back.
    private var typoSnapshot = ""
    // Last word committed (for the word-boundary semantic check).
    private var lastCommittedWord = ""
    private var lastCommitTimeMs = 0L
    // Last semantic (word-boundary) AI check, for the cooldown.
    private var lastSemanticCheckMs = 0L

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

    override fun onCreate() {
        super.onCreate()
        // Lexicon loading + indexing run on a background thread (v1.3.0):
        // typing must never wait for the 6.8 MB lexicon or the cloud sync.
        // Lookups simply return empty until the first prepare() finishes.
        val lexicon = Lexicon(this)
        candidateProvider = lexicon
        bgExecutor.execute {
            lexicon.prepare()
            val english = EnglishLexicon(this)
            english.prepare()
            englishLexicon = english
            // Cloud lexicon: fetch the manifest and build the cloud DB in
            // the background; the built-in lexicon serves lookups meanwhile.
            val cloud = CloudLexicon(this, lexicon, english)
            cloudLexicon = cloud
            cloud.sync { /* the cloud DB swaps in inside CloudLexicon */ }
        }
        clipboardHistory = ClipboardHistory(this)
        getSystemService(ClipboardManager::class.java)
            ?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        dismissVoicePopup()
        getSystemService(ClipboardManager::class.java)
            ?.removePrimaryClipChangedListener(clipListener)
        bgExecutor.shutdownNow()
        cloudLexicon?.shutdown()
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
        candidateBar.onEnglishLetterPick = { ch -> selectEnglishLetter(ch) }
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
                    VoiceState.AWAITING_COMMIT, VoiceState.COMMITTED -> commitPopupText()
                    else -> Unit
                }
            }
            onBackToKeyboard = { switchToKeyboard() }
            onModifyClick = { enterModifyMode() }
            onCloseClick = { closeVoicePopup() }
            onArrowLongPress = { continueSpeaking() }
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
            onHeightChange = { dpValue -> applyT9Height(dpValue) }
            onKeySizeChange = { dpValue -> applyKeySize(dpValue) }
            onExportLearned = { exportLearned() }
            onOfflineDownload = { downloadOfflinePack() }
            onClose = { closeSettingsPanel() }
        }
        root.addView(settingsPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, panelHeight))
        settingsPanel.setHeightDp(keyboard.currentRowPitchDp())
        settingsPanel.setKeySizeMaxDp(keyboard.maxKeyExtraDp())
        settingsPanel.setKeySizeDp(keyboard.currentKeyExtraDp())

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
        handler.removeCallbacks(onlineRequest)
        handler.removeCallbacks(typoCheck)
        online.cancel()
        digits.clear()
        enKeys.clear()
        enLocked.clear()
        enResult = null
        symbolsPage = false
        if (::keyboard.isInitialized) {
            keyboard.showMain(lang == Lang.ZH)
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
        stopVoiceSession(salvage = true, keepPopup = true)
        mode = Mode.VOICE
        keyboard.visibility = View.GONE
        voiceView.visibility = View.VISIBLE
        toggleBtn.setImageResource(R.drawable.ic_keyboard)
        candidateBar.update("", emptyList())
        resetVoiceUi()
    }

    private fun switchToKeyboard() {
        if (panelOpen) closeClipboardPanel()
        stopVoiceSession(salvage = true, keepPopup = true)
        mode = Mode.KEYBOARD
        voiceView.visibility = View.GONE
        keyboard.visibility = View.VISIBLE
        toggleBtn.setImageResource(R.drawable.ic_mic)
        resetInput()
    }

    private fun resetVoiceUi() {
        lastInterim = ""
        // A popup holding text waiting for the arrow (or already committed)
        // keeps its state across the mode switch; only the recording UI
        // resets. The popup is dismissed by the close button, not here.
        if (voiceState == VoiceState.AWAITING_COMMIT || voiceState == VoiceState.COMMITTED) {
            voiceView.recording = false
            voiceView.holdMode = false
            voiceView.setInterim("")
            syncPopupState()
            return
        }
        committed = false
        voiceState = VoiceState.IDLE
        voiceView.setInterim("")
        voiceView.recording = false
        voiceView.holdMode = false
        modifyMode = false
        modifyPending = false
        modifyPopupText = false
        pendingCommitText = null
        committedStart = -1
        committedText = ""
        popupBaseText = ""
        voiceView.readyToCommit = false
        if (BuildConfig.DOUBAO_API_KEY.isEmpty()) {
            voiceView.micEnabled = false
            voiceView.setStatus(getString(R.string.voice_not_configured), isError = true)
        } else {
            voiceView.micEnabled = true
            voiceView.setStatus(getString(R.string.voice_hint_idle))
        }
        syncPopupState()
    }

    /** "修改" entry: the big mic button lights up and records, but what is
     * said is an instruction for the backend, not text to commit. With text
     * waiting in the popup, the instruction modifies the popup text and the
     * result stays in the popup until the green-arrow tap; otherwise it
     * modifies the field text (v1.2.2 behaviour). */
    private fun enterModifyMode() {
        // Popup-target modify: text waiting for the arrow, or already
        // committed — the instruction edits the popup text; the result
        // stays in the popup until the green-arrow tap replaces the
        // previously committed segment.
        if (voiceState == VoiceState.AWAITING_COMMIT || voiceState == VoiceState.COMMITTED) {
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
            showModifyWindow()
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
        showModifyWindow()
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
                        if (modifyMode) {
                            // The instruction shows in the small window, never
                            // in the main popup, so a misrecognition is seen
                            // at once.
                            setModifyWindowText(text)
                        } else {
                            // Continue-speaking: the new interim appends to
                            // the text already in the popup.
                            setPopupText(popupBaseText + text)
                        }
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
        // Continue-speaking: the final is only the new part; the popup
        // (and the correction) see the whole text: base + new part.
        // In modify mode the text is an instruction, never appended.
        val fullText = if (modifyMode) text else popupBaseText + text
        popupBaseText = ""
        setPopupText(fullText)
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
            // Nothing new was said: the base text (if any) stays in the
            // popup and waits for the arrow again.
            if (fullText.isNotEmpty()) {
                setPopupText(fullText)
                pendingCommitText = fullText
                enterAwaitingCommit()
            } else {
                finishVoiceSession()
            }
            return
        }
        pendingOriginal = fullText
        if (SemanticCorrector.isQuestion(fullText)) {
            // Question / arithmetic: the answer waits in the popup; on any
            // failure onAskDone silently falls back to the correction.
            val id = sessionId
            handler.removeCallbacks(correctionTimeout)
            handler.postDelayed(correctionTimeout, CORRECTION_TIMEOUT_MS)
            corrector.ask(fullText) { answer ->
                handler.post { if (id == sessionId) onAskDone(fullText, answer) }
            }
        } else {
            requestCorrection(fullText)
        }
    }

    /** Backend semantic correction (with the Settings cleaning template);
     *  on any failure the original text waits in the popup instead. The
     *  sessionId guard drops late callbacks after a mode switch / input
     *  finish. */
    private fun requestCorrection(fullText: String) {
        val id = sessionId
        handler.removeCallbacks(correctionTimeout)
        handler.postDelayed(correctionTimeout, CORRECTION_TIMEOUT_MS)
        corrector.correct(fullText, correctLevel()) { corrected ->
            handler.post { if (id == sessionId) onCorrectionDone(fullText, corrected) }
        }
    }

    /** Offline decode finished: raw text waits in the popup for the
     *  green-arrow tap, no correction. */
    private fun onOfflineFinal(text: String) {
        // Modify needs the backend; offline the instruction is discarded and
        // the original stays untouched.
        if (modifyMode) {
            hideModifyWindow()
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
        // Continue-speaking: the decoded text is only the new part; the
        // popup sees base + new part.
        val fullText = popupBaseText + text
        popupBaseText = ""
        setPopupText(fullText)
        setPopupCorrecting(false)
        if (text.isEmpty()) {
            if (fullText.isNotEmpty()) {
                pendingCommitText = fullText
                enterAwaitingCommit()
            } else {
                finishVoiceSession()
            }
            return
        }
        pendingCommitText = fullText
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

    /** Ask result: the answer waits in the popup for the green-arrow tap,
     *  exactly like a cleaned dictation; nothing commits by itself. On any
     *  failure / timeout / no network, silently fall back to the normal
     *  correction. */
    private fun onAskDone(original: String, answer: String?) {
        handler.removeCallbacks(correctionTimeout)
        if (answer.isNullOrBlank()) {
            requestCorrection(original)
            return
        }
        setPopupText(answer)
        pendingCommitText = answer
        enterAwaitingCommit()
    }

    /** Modify result: popup-target modify updates the popup text and waits
     *  for the green-arrow tap; field-target modify replaces the field
     *  (v1.2.2). On any failure the original stays untouched. */
    private fun onModifyDone(original: String, modified: String?) {
        handler.removeCallbacks(modifyTimeout)
        hideModifyWindow()
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
                // The whole field is now the modified text; the popup shows
                // it and the arrow can replace it again (no duplicate).
                setPopupText(modified)
                pendingCommitText = modified
                committedStart = 0
                committedText = modified
                committed = true
                enterAwaitingCommit()
                return
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
        syncPopupState()
    }

    /** The pending text could not be modified (empty instruction / no
     *  original): put it back in the popup and keep waiting for the tap. */
    private fun restorePendingCommit() {
        hideModifyWindow()
        modifyMode = false
        modifyPopupText = false
        setPopupText(pendingCommitText ?: "")
        enterAwaitingCommit()
    }

    /**
     * Green-arrow tap (v1.3.0): the popup text goes on screen AND THE POPUP
     * STAYS, showing the just-committed text. Tapping again replaces the
     * previously committed segment in place (never appends a second copy);
     * the popup only goes away via the close button (or input finish /
     * window hidden).
     */
    private fun commitPopupText() {
        if (voiceState != VoiceState.AWAITING_COMMIT && voiceState != VoiceState.COMMITTED) return
        val text = pendingCommitText ?: return
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: return
        var placed = false
        if (committedStart >= 0 && committedText.isNotEmpty()) {
            placed = replaceCommittedSegment(text)
        }
        if (!placed) {
            // First commit (or the field moved under us): append at the
            // cursor and remember where the segment went.
            val beforeLen = ic.getTextBeforeCursor(100000, 0)?.length ?: 0
            ic.commitText(text, 1)
            committedStart = beforeLen
            placed = true
        }
        if (placed) {
            committedText = text
            committed = true
            voiceState = VoiceState.COMMITTED
            voiceView.readyToCommit = true
            voiceView.setStatus(getString(R.string.voice_ready_to_commit))
            setPopupStatusText(getString(R.string.voice_ready_to_commit))
            syncPopupState()
        }
    }

    /**
     * Replace the previously committed segment with the new popup text.
     * Verifies the field still holds the old segment at the recorded
     * position first; returns false (caller appends instead) if the field
     * moved under us or the text is too long to position reliably.
     */
    private fun replaceCommittedSegment(newText: String): Boolean {
        val ic = currentInputConnection ?: return false
        val start = committedStart
        if (start < 0 || committedText.isEmpty()) return false
        val before = ic.getTextBeforeCursor(100000, 0)?.toString() ?: return false
        if (before.length >= 100000) return false
        val after = ic.getTextAfterCursor(100000, 0)?.toString() ?: ""
        val fieldText = before + after
        if (start + committedText.length > fieldText.length) return false
        if (fieldText.substring(start, start + committedText.length) != committedText) return false
        val cursor = before.length
        val toDeleteBefore = cursor - start
        if (toDeleteBefore < 0) return false
        val toDeleteAfter = (start + committedText.length) - cursor
        if (toDeleteAfter < 0) return false
        ic.beginBatchEdit()
        return try {
            ic.deleteSurroundingText(toDeleteBefore, toDeleteAfter)
            ic.commitText(newText, 1)
            true
        } catch (e: Exception) {
            false
        } finally {
            ic.endBatchEdit()
        }
    }

    /**
     * Close-button enabled state follows the popup (v1.3.0): pressable only
     * while the popup is showing AND the commit flow has started
     * (AWAITING_COMMIT / COMMITTED); greyed out at all other times.
     */
    private fun syncPopupState() {
        if (!::voiceView.isInitialized) return
        val popupShowing = voicePopup?.isShowing == true
        val inCommitFlow = voiceState == VoiceState.AWAITING_COMMIT || voiceState == VoiceState.COMMITTED
        voiceView.closeEnabled = popupShowing && inCommitFlow
    }

    /** Close button (bottom row, next to 修改): dismiss the popup and end
     *  the session. Only reachable while closeEnabled; the popup itself
     *  never dismisses on tap-away. */
    private fun closeVoicePopup() {
        if (!voiceView.closeEnabled) return
        dismissVoicePopup()
        hideModifyWindow()
        finishVoiceSession()
    }

    /** Long-press the green arrow: keep talking. The new speech appends to
     *  the text already in the popup; the next arrow tap commits the whole
     *  popup text (replacing the previously committed segment). */
    private fun continueSpeaking() {
        if (voiceState != VoiceState.AWAITING_COMMIT && voiceState != VoiceState.COMMITTED) return
        popupBaseText = popupText?.text?.toString().orEmpty()
        voiceView.readyToCommit = false
        voiceView.setStatus(getString(R.string.voice_hint_idle))
        voiceState = VoiceState.IDLE
        syncPopupState()
        startRecording(hold = false)
    }

    /** Cleaning template from Settings (normal / medium / strong, default
     *  normal). Only the /correct call sends it; /modify is unaffected. */
    private fun correctLevel(): String {
        val level = getSharedPreferences(SettingsPanelView.PREFS, Context.MODE_PRIVATE)
            .getString(SettingsPanelView.KEY_CORRECT_LEVEL, "normal") ?: "normal"
        return if (level in SettingsPanelView.CORRECT_LEVELS) level else "normal"
    }

    /**
     * Voice session finished: back to idle. The popup is NOT dismissed
     * here: it stays until the close button (or a mode switch / input
     * finish), so the user can re-read, modify, or re-commit the text.
     */
    private fun finishVoiceSession() {
        handler.removeCallbacks(correctionTimeout)
        handler.removeCallbacks(modifyTimeout)
        hideModifyWindow()
        modifyPending = false
        pendingOriginal = null
        offlineMode = false
        offlineChunks.clear()
        corrector.cancel()
        cleanupSession()
        // Text waiting or already committed: keep the popup and its state;
        // only the close button (or a mode switch) ends it.
        if (voiceState == VoiceState.AWAITING_COMMIT || voiceState == VoiceState.COMMITTED) {
            syncPopupState()
            return
        }
        pendingCommitText = null
        committedStart = -1
        committedText = ""
        popupBaseText = ""
        voiceView.readyToCommit = false
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
        dismissVoicePopup()
        syncPopupState()
    }

    private fun onAsrClosed() {
        // Correction / modify in flight owns the session; the close is
        // expected after the final packet and must not tear down the pending
        // commit. In modify mode the interim is an instruction, never text
        // to salvage.
        if (pendingOriginal != null || modifyPending) return
        // Text waiting in the popup for the arrow tap, or already committed:
        // a late close must not tear it down.
        if (voiceState == VoiceState.AWAITING_COMMIT || voiceState == VoiceState.COMMITTED) return
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
        // The salvaged text is safely in the field; the popup goes.
        dismissVoicePopup()
        syncPopupState()
    }

    private fun failSession(message: String) {
        // Final text already received: a late ASR error must not discard it;
        // the correction / modify flow commits the original on its own failure.
        if (pendingOriginal != null || modifyPending) return
        // Text waiting in the popup for the arrow tap, or already committed:
        // a late ASR error must not dismiss it.
        if (voiceState == VoiceState.AWAITING_COMMIT || voiceState == VoiceState.COMMITTED) return
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
        // Nothing is committed on failure; the error shows on the voice view
        // and the popup goes (there is no text to preserve).
        voiceView.setStatus(message, isError = true)
        dismissVoicePopup()
        syncPopupState()
    }

    /**
     * Mode switch / input finished: stop recording FIRST, then tear down.
     * @param keepPopup when true (keyboard <-> voice switch only), a popup
     * holding text waiting for the arrow — or already committed — stays up
     * with its state, so the user can come back and tap the arrow or the
     * close button. Input finish / window hidden always dismisses it.
     */
    private fun stopVoiceSession(salvage: Boolean, keepPopup: Boolean = false) {
        handler.removeCallbacks(processingTimeout)
        handler.removeCallbacks(correctionTimeout)
        handler.removeCallbacks(modifyTimeout)
        hideModifyWindow()
        modifyPending = false
        val wasModify = modifyMode
        modifyMode = false
        modifyPopupText = false
        // Text waiting for the arrow, or already committed: it is never
        // salvaged (the user did not tap the arrow).
        val wasAwaiting = voiceState == VoiceState.AWAITING_COMMIT || voiceState == VoiceState.COMMITTED
        val wasActive = asrClient != null || voiceState != VoiceState.IDLE
        // Correction in flight: the final text beats the last interim, and
        // the late correction callback is dropped by the sessionId bump.
        // In modify mode nothing is salvaged: the interim is an instruction,
        // not text to commit.
        val salvageText = pendingOriginal ?: lastInterim
        val keep = keepPopup && wasAwaiting
        if (!keep) {
            pendingOriginal = null
            pendingCommitText = null
            committedStart = -1
            committedText = ""
            popupBaseText = ""
        }
        offlineMode = false
        offlineChunks.clear()
        corrector.cancel()
        sessionId++
        capture?.stop()
        capture = null
        if (!keep && salvage && wasActive && !committed && !wasModify && !wasAwaiting && salvageText.isNotEmpty()) {
            commitOnce(salvageText)
        }
        asrClient?.cancel()
        asrClient = null
        if (keep) {
            // Popup and its state stay; only the recording/session stopped.
            syncPopupState()
            return
        }
        voiceState = VoiceState.IDLE
        dismissVoicePopup()
        if (::voiceView.isInitialized) {
            voiceView.recording = false
            voiceView.readyToCommit = false
            voiceView.setInterim("")
        }
        syncPopupState()
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
        val existing = voicePopup
        if (existing != null && existing.isShowing) {
            // Continue-speaking / modify from a waiting popup: keep the
            // text already shown; the interim appends to it.
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
        voicePopup?.dismiss()
        voicePopup = null
        popupText = null
        popupScroll = null
        popupStatus = null
    }

    // Small window for the modify instruction (v1.3.0): while the user
    // speaks the instruction, the recognized text shows here — never in
    // the main popup — so a misrecognition is spotted immediately. Placed
    // near the top of the screen, below the status bar, so it never
    // overlaps the main popup above the keyboard.
    private var modifyWindow: PopupWindow? = null
    private var modifyWindowText: TextView? = null

    private fun showModifyWindow() {
        val existing = modifyWindow
        if (existing != null && existing.isShowing) {
            setModifyWindowText("")
            return
        }
        val pad = dp(10f)
        val text = TextView(this).apply {
            textSize = 15f
            setTextColor(getColor(R.color.key_text))
            hint = getString(R.string.voice_edit_hint)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(getColor(R.color.keyboard_bg))
                setStroke(dp(1f), getColor(R.color.divider))
            }
            setPadding(pad, pad, pad, pad)
            addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val anchor = voiceView.rootView
        val margin = dp(12f)
        val statusBar = run {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else dp(24f)
        }
        val width = if (anchor.width > 0) anchor.width - 2 * margin else LinearLayout.LayoutParams.MATCH_PARENT
        val popup = PopupWindow(content, width, LinearLayout.LayoutParams.WRAP_CONTENT)
        popup.isFocusable = false
        popup.isOutsideTouchable = false
        modifyWindow = popup
        modifyWindowText = text
        setModifyWindowText("")
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, if (anchor.width > 0) margin else 0, statusBar + dp(8f))
    }

    private fun setModifyWindowText(text: String) {
        modifyWindowText?.text = text
        if (modifyWindow?.isShowing == true) modifyWindow?.update()
    }

    private fun hideModifyWindow() {
        modifyWindow?.dismiss()
        modifyWindow = null
        modifyWindowText = null
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
        settingsPanel.setKeySizeMaxDp(keyboard.maxKeyExtraDp())
        settingsPanel.setKeySizeDp(keyboard.currentKeyExtraDp())
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
     *  (the 9-key page is 5 x its pitch; the symbol page is its own
     *  height). Call after every page switch, or the window keeps the
     *  old height. */
    private fun resizeKeyboardToPage() {
        if (!::keyboard.isInitialized) return
        keyboard.layoutParams.height = keyboard.currentPanelHeightPx
        keyboard.requestLayout()
    }

    /** T9 height tool (v1.3.0: the only keyboard): rebuild the 9-key page
     *  at the new pitch, then resize every panel to the new formula height. */
    private fun applyT9Height(dpValue: Float) {
        keyboard.setRowPitchDp(dpValue)
        val h = keyboard.panelHeightPx
        keyboard.layoutParams.height = keyboard.currentPanelHeightPx
        voiceView.layoutParams.height = h
        clipboardPanel.layoutParams.height = h
        settingsPanel.layoutParams.height = h
        settingsPanel.setHeightDp(dpValue)
        // The key-size max depends on the pitch; refresh it (and the
        // label, in case the extra was clamped to the new max).
        settingsPanel.setKeySizeMaxDp(keyboard.maxKeyExtraDp())
        settingsPanel.setKeySizeDp(keyboard.currentKeyExtraDp())
        keyboard.requestLayout()
    }

    /** Key-size tool (v1.3.0): keys grow, gaps shrink, total height never
     *  changes. The panel's max follows the current pitch. */
    private fun applyKeySize(dpValue: Float) {
        keyboard.setKeyExtraDp(dpValue)
        settingsPanel.setKeySizeDp(keyboard.currentKeyExtraDp())
        settingsPanel.setKeySizeMaxDp(keyboard.maxKeyExtraDp())
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
                // One 9-key keyboard for both languages (v1.3.0): the same
                // keys, the same letter sublabels; only the candidate logic
                // changes. Pending input is flushed first.
                flushBuffer()
                lang = if (lang == Lang.ZH) Lang.EN else Lang.ZH
                enKeys.clear()
                enLocked.clear()
                enResult = null
                keyboard.showMain(lang == Lang.ZH)
                resizeKeyboardToPage()
                refreshCandidates()
            }
            key == "sym" -> {
                flushBuffer()
                symbolsPage = true
                keyboard.showSymbols()
                resizeKeyboardToPage()
            }
            key == "back" -> {
                symbolsPage = false
                keyboard.showMain(lang == Lang.ZH)
                resizeKeyboardToPage()
            }
            symbolsPage -> {
                flushBuffer()
                commit(key)
            }
            numericField && key.length == 1 && key[0] in '0'..'9' -> {
                // Integer field: tap a key, the digit on it goes straight in.
                flushBuffer()
                commit(key)
            }
            key == "1" -> {
                flushBuffer()
                // The 1 key is the punctuation key (v1.3.0): Chinese comma
                // in ZH mode, a full stop in EN mode. The digit 1 itself
                // comes from swiping up on the key.
                commit(if (lang == Lang.EN) "." else "，")
            }
            key == "0" -> {
                flushBuffer()
                commit("0")
            }
            key.length == 1 && key[0] in '2'..'9' -> {
                if (lang == Lang.EN) {
                    onEnglishDigit(key[0])
                } else if (digits.length < MAX_DIGITS) {
                    digits.append(key)
                    refreshCandidates()
                }
            }
        }
    }

    /** Swipe up on a digit key inputs that digit directly, in any field. */
    private fun onDigitSwipeUp(digit: String) {
        flushBuffer()
        commit(digit)
    }

    private fun onDelete() {
        if (lang == Lang.EN && enKeys.isNotEmpty()) {
            // English step-back (v1.3.0): delete undoes ONE position.
            // A locked letter is unlocked first (so that position's letter
            // can be chosen again); an unlocked position drops its key.
            // The committed word text is untouched.
            val last = enLocked.lastIndex
            if (last >= 0 && enLocked[last] != null) {
                enLocked[last] = null
            } else {
                enKeys.deleteCharAt(enKeys.length - 1)
                if (last >= 0) enLocked.removeAt(last)
            }
            refreshCandidates()
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
        if (digits.isNotEmpty() || enKeys.isNotEmpty()) {
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
        if (lang == Lang.EN && enKeys.isNotEmpty()) {
            // English: space commits the top word (with a trailing space,
            // T9-style); undecodable input commits nothing.
            commitEnglishWord()
            return
        }
        if (digits.isNotEmpty()) {
            commitFirstOrRaw()
        } else {
            commit(" ")
        }
    }

    private fun onEnter() {
        if (digits.isNotEmpty() || enKeys.isNotEmpty()) {
            // Never commit raw digits (v1.3.0 no-digit rule): commit the top
            // candidate if there is one, otherwise do nothing — the input
            // stays pending for the user to pick or delete.
            if (!commitFirstOrRaw()) return
        }
        val ic = currentInputConnection ?: return
        val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    // ---- English 9-key (v1.3.0) --------------------------------------------

    /** Press a key: add one letter position. The dropdown then lists the
     *  letters that position can be; the user taps one to lock it. */
    private fun onEnglishDigit(digit: Char) {
        if (enKeys.length >= MAX_DIGITS) return
        enKeys.append(digit)
        enLocked.add(null)
        refreshCandidates()
    }

    /** Dropdown tap: lock the letter for the newest (unlocked) position. */
    private fun selectEnglishLetter(letter: Char) {
        if (enKeys.isEmpty()) return
        val last = enLocked.lastIndex
        if (last < 0 || enLocked[last] != null) return
        enLocked[last] = letter.lowercaseChar()
        refreshCandidates()
    }

    /** Commit the top English word (auto-space, T9-style). Returns false —
     *  and commits nothing — when no word is available: undecodable input
     *  never puts digits on screen. */
    private fun commitEnglishWord(): Boolean {
        val result = enResult
        val word = result?.common?.firstOrNull() ?: result?.rare?.firstOrNull()
        // No word candidate: fall back to the letters the user locked by
        // hand in the dropdown -- those are certainly right. Nothing locked
        // either: commit nothing (never digits).
        val text = word ?: enLocked.filterNotNull().joinToString("").takeIf { it.isNotEmpty() } ?: return false
        if (word != null) candidateProvider?.rememberTap(word)
        commit(capitalizeEnglish(text) + " ")
        lastCommittedWord = text
        lastCommitTimeMs = System.currentTimeMillis()
        enKeys.clear()
        enLocked.clear()
        enResult = null
        refreshCandidates()
        scheduleTypoCheck()
        return true
    }

    /** Sentence-start auto-capitalization: the first letter is upper-case
     *  at the start of the field, or right after ". " / "! " / "? ";
     *  everywhere else the word stays lower-case. */
    private fun capitalizeEnglish(word: String): String {
        if (word.isEmpty()) return word
        val before = currentInputConnection?.getTextBeforeCursor(10, 0)?.toString().orEmpty()
        val trimmed = before.trimEnd()
        val atStart = trimmed.isEmpty()
        val afterTerminator = trimmed.isNotEmpty() && trimmed.last() in ".!?"
        return if (atStart || afterTerminator) {
            word.replaceFirstChar { it.uppercaseChar() }
        } else {
            word
        }
    }

    // ---- candidates & committing --------------------------------------------

    private fun refreshCandidates() {
        // The user is still typing: cancel any pending AI check and let the
        // generation bump invalidate an in-flight AI request.
        handler.removeCallbacks(typoCheck)
        typoGen++
        if (lang == Lang.EN) {
            refreshEnglishCandidates()
            return
        }
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
        // Nothing matched: local correction on a background thread, and the
        // AI typo check after a pause. Both are candidates, never forced.
        if (d.isNotEmpty() && result.candidates.isEmpty()) {
            scheduleLocalCorrectionZh(d)
            scheduleTypoCheck()
        }
    }

    /** English candidates (v1.3.0): letter-combination enumeration + word
     *  utility (frequency tiers, learned first). Local adjacent-key
     *  correction fills in when nothing matches; the AI check follows
     *  after a pause. */
    private fun refreshEnglishCandidates() {
        val engine = englishLexicon
        val d = enKeys.toString()
        if (engine == null || d.isEmpty()) {
            enResult = null
            candidateBar.updateEnglish(null)
            return
        }
        val learned = (candidateProvider as? Lexicon)?.learnedMap().orEmpty()
        var result = engine.lookup(d, enLocked, learned)
        var corrected = false
        if (result.common.isEmpty() && result.rare.isEmpty()) {
            // No good word: try one adjacent-key substitution (a slip of
            // the finger). Only an unambiguous strong match is used.
            val fix = engine.correctByAdjacentKey(d, enLocked, learned)
            if (fix != null) {
                val fixed = engine.lookup(fix.digits, fix.locked, learned)
                if (fixed.common.isNotEmpty() || fixed.rare.isNotEmpty()) {
                    result = fixed
                    corrected = true
                }
            }
        }
        enResult = result
        candidateBar.updateEnglish(result, enLocked.filterNotNull().joinToString(""), corrected)
        if (result.common.isEmpty() && result.rare.isEmpty()) {
            scheduleTypoCheck()
        }
    }

    /** Chinese local correction on a background thread (v1.3.0): only for
     *  input that cannot be spelled at all. The corrected candidates are
     *  shown when they come back — but only if the user hasn't typed on. */
    private fun scheduleLocalCorrectionZh(d: String) {
        val lex = candidateProvider as? Lexicon ?: return
        val before = currentInputConnection?.getTextBeforeCursor(20, 0)?.toString().orEmpty()
        bgExecutor.execute {
            val fix = try {
                lex.suggestCorrection(d, before)
            } catch (e: Exception) {
                null
            }
            if (fix != null) {
                handler.post {
                    if (digits.toString() == d && lang == Lang.ZH) {
                        candidateBar.update(
                            fix.first,
                            fix.second,
                            emptyList(),
                            0
                        )
                    }
                }
            }
        }
    }

    /** Arm the AI typo check after a 500ms pause (debounced). The check
     *  itself runs in runTypoCheck(); typing again cancels this schedule. */
    private fun scheduleTypoCheck() {
        typoSnapshot = if (lang == Lang.EN) enKeys.toString() else digits.toString()
        typoGen++
        handler.removeCallbacks(typoCheck)
        handler.postDelayed(typoCheck, TYPO_DEBOUNCE_MS)
    }

    /**
     * AI typo check (v1.3.0): the judge, not the typist. Runs only after a
     * 500ms pause and only for suspicious input. Sends a small slice — the
     * sentence around the suspect word — to the backend /typo endpoint
     * (same token auth as /correct). The result appears as an AI-marked
     * candidate; it never replaces anything by itself. Offline or on any
     * failure: silent, local layer only.
     *
     * Two cases share this pipeline (the "word" just means something
     * different): pinyin syllables / Chinese words / English words.
     *  - Pending input: the suspect is what the user is typing now, and
     *    only when the local layer already gave up (no candidates). A
     *    legal syllable like hou vs hao is NEVER sent from here — it waits
     *    for the word boundary, so "你后" is not corrected to "你好".
     *  - Word boundary: no pending input, but a word was just committed.
     *    The sentence + that word go to the AI, which judges whether the
     *    word fits the sentence (a legal but semantically wrong word).
     *    Restrained by a cooldown (one semantic check per 15s at most).
     */
    private fun runTypoCheck() {
        typoInFlight = false
        if (!networkAvailable()) return
        val gen = typoGen
        val pendingZh = lang == Lang.ZH && digits.isNotEmpty()
        val pendingEn = lang == Lang.EN && enKeys.isNotEmpty()
        val suspect: String
        val digitsArg: String?
        val mode: String
        if (pendingZh) {
            if (lastLocal.candidates.isNotEmpty()) return
            suspect = candidateProvider?.pinyinFor(digits.toString()) ?: digits.toString()
            digitsArg = digits.toString()
            mode = "pinyin"
        } else if (pendingEn) {
            val r = enResult
            if (r != null && (r.common.isNotEmpty() || r.rare.isNotEmpty())) return
            val lockedLetters = enLocked.filterNotNull().joinToString("")
            suspect = if (lockedLetters.isNotEmpty()) lockedLetters else enKeys.toString()
            digitsArg = enKeys.toString()
            mode = "english"
        } else {
            // Word boundary: the last committed word, if it was just typed
            // and the sentence is long enough to judge.
            if (lastCommittedWord.isEmpty()) return
            if (System.currentTimeMillis() - lastCommitTimeMs > TYPO_WORD_WINDOW_MS) return
            if (System.currentTimeMillis() - lastSemanticCheckMs < TYPO_SEMANTIC_COOLDOWN_MS) return
            suspect = lastCommittedWord
            digitsArg = null
            mode = "word"
            lastSemanticCheckMs = System.currentTimeMillis()
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(60, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(30, 0)?.toString().orEmpty()
        val sentence = before + after
        if (sentence.isBlank()) return
        lastTypoSentence = sentence
        typoInFlight = true
        corrector.correctTypo(sentence, suspect, digitsArg, mode) { correctedWord ->
            handler.post {
                typoInFlight = false
                if (gen != typoGen) return@post  // user kept typing; stale
                if (correctedWord.isNullOrBlank() || correctedWord == suspect) return@post
                // Pending input must be unchanged, or the suggestion is stale.
                if (pendingZh || pendingEn) {
                    val current = if (lang == Lang.EN) enKeys.toString() else digits.toString()
                    if (current != typoSnapshot) return@post
                }
                showAiCandidate(correctedWord, suspect)
            }
        }
    }

    /** Show an AI suggestion as a marked candidate in the bar. Tapping it
     *  applies it (see applyAiCandidate); it never goes on screen alone. */
    private fun showAiCandidate(correctedWord: String, suspect: String) {
        val c = Candidate(word = correctedWord, isAi = true, aiOriginal = suspect)
        candidateBar.addAiCandidate(c)
    }

    /** Apply an AI suggestion the user tapped. Pending input: the corrected
     *  word commits like a normal candidate. Word boundary: the suspect
     *  word in the field is replaced by the corrected word. */
    private fun applyAiCandidate(c: Candidate) {
        val corrected = c.word
        val suspect = c.aiOriginal.orEmpty()
        if (digits.isNotEmpty() || enKeys.isNotEmpty()) {
            // Pending input: commit the corrected word, drop the mistyped input.
            candidateProvider?.rememberTap(corrected)
            if (lang == Lang.EN) {
                commit(capitalizeEnglish(corrected) + " ")
                enKeys.clear()
                enLocked.clear()
                enResult = null
            } else {
                commit(corrected)
                digits.clear()
            }
            lastCommittedWord = corrected
            lastCommitTimeMs = System.currentTimeMillis()
            refreshCandidates()
            return
        }
        // Word already in the field: replace the suspect word in place.
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(100, 0)?.toString().orEmpty()
        val withSpace = suspect.isNotEmpty() && before.endsWith("$suspect ", ignoreCase = true)
        val plain = suspect.isNotEmpty() && before.endsWith(suspect, ignoreCase = true)
        if (!withSpace && !plain) return
        // The suspect must sit on a word boundary (start of text or a
        // non-letter before it), so `in` never matches inside `within`.
        val startIdx = before.length - (suspect.length + if (withSpace) 1 else 0)
        if (startIdx > 0 && before[startIdx - 1].isLetter()) return
        val deleteLen = suspect.length + if (withSpace) 1 else 0
        ic.deleteSurroundingText(deleteLen, 0)
        val out = if (lang == Lang.EN) capitalizeEnglish(corrected) else corrected
        commit(out + if (withSpace) " " else "")
        candidateProvider?.rememberTap(corrected)
        lastCommittedWord = corrected
        lastCommitTimeMs = System.currentTimeMillis()
        refreshCandidates()
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

    /** One entry point for every candidate tap (Chinese, English, AI).
     *  AI suggestions go to applyAiCandidate; the rest commit normally. */
    private fun commitCandidate(c: Candidate) {
        if (c.isAi) {
            applyAiCandidate(c)
            return
        }
        candidateProvider?.rememberTap(c.word)
        if (lang == Lang.EN) {
            commit(capitalizeEnglish(c.word) + " ")
            enKeys.clear()
            enLocked.clear()
            enResult = null
        } else {
            commit(c.word)
            digits.clear()
        }
        lastCommittedWord = c.word
        lastCommitTimeMs = System.currentTimeMillis()
        refreshCandidates()
        // Word boundary: after a pause the AI may judge the sentence.
        scheduleTypoCheck()
    }

    /** Commit the top candidate if there is one. Returns false (and commits
     *  nothing) when the input is undecodable — raw digits never go on
     *  screen (v1.3.0 no-digit rule). */
    private fun commitFirstOrRaw(): Boolean {
        if (lang == Lang.EN) return commitEnglishWord()
        val d = digits.toString()
        // A user-chosen segmentation must not be overridden by the global top word.
        val first = if (segIndex > 0) lastLocal.candidates.firstOrNull()
        else candidateProvider?.lookup(d)?.candidates?.firstOrNull()
        if (first == null) return false
        candidateProvider?.rememberTap(first.word)
        commit(first.word)
        lastCommittedWord = first.word
        lastCommitTimeMs = System.currentTimeMillis()
        digits.clear()
        refreshCandidates()
        scheduleTypoCheck()
        return true
    }

    private fun flushBuffer() {
        if (digits.isNotEmpty() || enKeys.isNotEmpty()) commitFirstOrRaw()
    }

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    companion object {
        private const val MAX_DIGITS = 20
        private const val ONLINE_DEBOUNCE_MS = 100L
        private const val FINAL_TIMEOUT_MS = 8000L
        // Backstop just above the corrector's own 15s call timeout.
        private const val CORRECTION_TIMEOUT_MS = 16000L
        // AI typo check: wait this long after the last keypress (a pause)
        // before asking the AI; a word counts as "just committed" for this
        // long; at most one word-boundary semantic check per cooldown.
        private const val TYPO_DEBOUNCE_MS = 500L
        private const val TYPO_WORD_WINDOW_MS = 3000L
        private const val TYPO_SEMANTIC_COOLDOWN_MS = 15000L
    }
}
