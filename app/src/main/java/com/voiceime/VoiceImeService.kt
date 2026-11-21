package com.voiceime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.widget.TextView

/**
 * VoiceIME input method shell (Chunk 1).
 * Registers as a system IME; the real keyboard UI lands in Chunk 2.
 */
class VoiceImeService : InputMethodService() {

    override fun onCreateInputView(): View {
        return TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 18f
            setPadding(32, 48, 32, 48)
        }
    }
}
