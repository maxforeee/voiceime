package com.voiceime

import android.Manifest
import android.content.ClipboardManager
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
    private enum class VoiceState { IDLE, RECORDING_HOLD, RECORDING_TAP, PROCESSING }

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
    private val processingTimeout = Runnable {
        if (voiceState == VoiceState.PROCESSING) failSession(getString(R.string.voice_timeout))
    }

    // Semantic correction of the ASR final text. While pendingOriginal is
    // set, the correction flow owns the session: late ASR close/error events
    // are ignored and the original text is the fallback that gets committed.
    private val corrector = SemanticCorrector()
    private var pendingOriginal: String? = null
    private val correctionTimeout = Runnable {
        val original = pendingOriginal ?: return@Runnable
        commitOnce(original)
        finishVoiceSession()
    }

    // Live-text popup: a separate window above the keyboard, so the fixed
    // panel heights are never affected. It owns text display while recording.
    private var voicePopup: PopupWindow? = null
    private var popupText: TextView? = null
    private var popupScroll: ScrollView? = null
    private var popupStatus: TextView? = null
    private var popupEnd: TextView? = null
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
                    else -> Unit
                }
            }
            onBackToKeyboard = { switchToKeyboard() }
            onModifyClick = {
                Toast.makeText(this@VoiceImeService, getString(R.string.voice_edit_soon), Toast.LENGTH_SHORT).show()
            }
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
            if (numericField && lang == Lang.EN) keyboard.showMain(true)
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
        setPopupCorrecting(true)
        capture?.stop()
        capture = null
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
        voiceView.setStatus(getString(R.string.voice_correcting))
        if (text.isEmpty()) {
            finishVoiceSession()
            return
        }
        // Backend semantic correction; on any failure the original text is
        // committed instead. The sessionId guard drops late callbacks after
        // a mode switch / input finish.
        pendingOriginal = text
        val id = sessionId
        handler.removeCallbacks(correctionTimeout)
        handler.postDelayed(correctionTimeout, CORRECTION_TIMEOUT_MS)
        corrector.correct(text) { corrected ->
            handler.post { if (id == sessionId) onCorrectionDone(text, corrected) }
        }
    }

    private fun onCorrectionDone(original: String, corrected: String?) {
        handler.removeCallbacks(correctionTimeout)
        val out = corrected ?: original
        setPopupText(out)
        commitOnce(out)
        finishVoiceSession()
    }

    /** Correction finished (or gave up): commit happened, back to idle. */
    private fun finishVoiceSession() {
        handler.removeCallbacks(correctionTimeout)
        pendingOriginal = null
        corrector.cancel()
        cleanupSession()
        voiceState = VoiceState.IDLE
        voiceView.recording = false
        voiceView.micEnabled = true
        voiceView.setInterim("")
        setPopupCorrecting(false)
        voiceView.setStatus(
            getString(if (committed) R.string.voice_done else R.string.voice_hint_idle)
        )
        // Let the final text stay readable for a moment before the popup goes.
        handler.postDelayed(popupDismiss, POPUP_LINGER_MS)
    }

    private fun onAsrClosed() {
        // Correction in flight owns the session; the close is expected after
        // the final packet and must not tear down the pending commit.
        if (pendingOriginal != null) return
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
        handler.postDelayed(popupDismiss, POPUP_LINGER_MS)
    }

    private fun failSession(message: String) {
        // Final text already received: a late ASR error must not discard it;
        // the correction flow commits the original on its own failure.
        if (pendingOriginal != null) return
        handler.removeCallbacks(processingTimeout)
        sessionId++
        capture?.stop()
        capture = null
        asrClient?.cancel()
        asrClient = null
        voiceState = VoiceState.IDLE
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
        val wasActive = asrClient != null || voiceState != VoiceState.IDLE
        // Correction in flight: the final text beats the last interim, and
        // the late correction callback is dropped by the sessionId bump.
        val salvageText = pendingOriginal ?: lastInterim
        pendingOriginal = null
        corrector.cancel()
        sessionId++
        capture?.stop()
        capture = null
        if (salvage && wasActive && !committed && salvageText.isNotEmpty()) {
            commitOnce(salvageText)
        }
        asrClient?.cancel()
        asrClient = null
        voiceState = VoiceState.IDLE
        dismissVoicePopup()
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
        // The scroll area grows with the text up to a cap, then scrolls
        // internally, so the popup never grows over the app's text field.
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(dp(120f), MeasureSpec.AT_MOST)
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
            text = getString(R.string.voice_correcting)
            visibility = View.GONE
            setPadding(0, dp(4f), 0, 0)
        }
        val end = TextView(this).apply {
            text = getString(R.string.voice_popup_end)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.on_accent))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(getColor(R.color.accent))
            }
            setPadding(dp(18f), dp(6f), dp(18f), dp(6f))
            isClickable = true
            setOnClickListener { stopRecording() }
        }
        val endRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(6f), 0, 0)
            addView(end, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
            addView(endRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val anchor = voiceView.rootView
        val width = if (anchor.width > 0) anchor.width else LinearLayout.LayoutParams.MATCH_PARENT
        val popup = PopupWindow(content, width, LinearLayout.LayoutParams.WRAP_CONTENT)
        popup.isFocusable = false
        popup.isOutsideTouchable = false
        voicePopup = popup
        popupText = text
        popupScroll = scroll
        popupStatus = status
        popupEnd = end
        setPopupText("")
        setPopupCorrecting(false)
        // Bottom gravity + the input view's own height puts the popup just
        // above the keyboard, whether the frame is screen- or window-relative.
        popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.START, 0, anchor.height + dp(8f))
    }

    private fun setPopupText(text: String) {
        val tv = popupText ?: return
        tv.text = text
        popupScroll?.post { popupScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setPopupCorrecting(correcting: Boolean) {
        popupStatus?.visibility = if (correcting) View.VISIBLE else View.GONE
        popupEnd?.isEnabled = !correcting
        popupEnd?.alpha = if (correcting) 0.5f else 1f
    }

    private fun dismissVoicePopup() {
        handler.removeCallbacks(popupDismiss)
        voicePopup?.dismiss()
        voicePopup = null
        popupText = null
        popupScroll = null
        popupStatus = null
        popupEnd = null
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

    /** Keyboard height tool: rebuild the keyboard at the new pitch, then
     *  resize every panel to the new formula height. */
    private fun applyKeyboardHeight(dpValue: Float) {
        keyboard.setRowPitchDp(dpValue)
        val h = keyboard.panelHeightPx
        keyboard.layoutParams.height = h
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
            }
            key == "sym" -> {
                flushBuffer()
                symbolsPage = true
                keyboard.showSymbols()
            }
            key == "back" -> {
                symbolsPage = false
                keyboard.showMainForLang(lang == Lang.ZH)
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
