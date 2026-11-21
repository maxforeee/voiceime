package com.voiceime.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * Offline speech recognition with the Sherpa-ONNX Paraformer int8 model
 * (Chinese + English + Cantonese, single file model.int8.onnx, ~234 MB).
 *
 * The model is NOT bundled in the APK and never auto-downloaded: the user
 * downloads it from Settings, it lives in the app's internal files dir.
 * The recognizer is created fresh per utterance and released right after
 * decoding, so the ~234 MB model is not held in memory between sessions.
 * Offline results are raw text: no backend semantic correction without a
 * network, the popup marks the session as offline recognition.
 */
class OfflineAsr(private val context: Context) {

    /**
     * Decode captured 16-bit mono PCM chunks (ByteArray, only the first n
     * bytes of each chunk are valid; the caller already copied them) to text.
     * Returns "" on any failure (bad/missing model, decode error).
     */
    fun decode(chunks: List<ByteArray>): String {
        val dir = modelDir(context)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    File(dir, MODEL_FILE).absolutePath
                ),
                tokens = File(dir, TOKENS_FILE).absolutePath
            )
        )
        // assetManager defaults to null -> the recognizer loads from file paths.
        val recognizer = OfflineRecognizer(config = config)
        try {
            val stream = recognizer.createStream()
            try {
                val samples = toFloatArray(chunks)
                if (samples.isEmpty()) return ""
                stream.acceptWaveform(samples, 16000)
                recognizer.decode(stream)
                return recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    }

    /** Little-endian 16-bit PCM -> float samples in [-1, 1]. */
    private fun toFloatArray(chunks: List<ByteArray>): FloatArray {
        var total = 0
        for (c in chunks) total += c.size / 2
        val out = FloatArray(total)
        var idx = 0
        for (c in chunks) {
            var i = 0
            while (i + 1 < c.size) {
                val lo = c[i].toInt() and 0xFF
                val hi = c[i + 1].toInt()
                val s = (hi shl 8) or lo
                out[idx++] = s / 32768f
                i += 2
            }
        }
        return if (idx == total) out else out.copyOf(idx)
    }

    companion object {
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"

        fun modelDir(context: Context): File =
            File(context.filesDir, "offline-asr")

        /** True when the downloaded pack is present and plausibly complete. */
        fun isModelReady(context: Context): Boolean {
            val dir = modelDir(context)
            val model = File(dir, MODEL_FILE)
            val tokens = File(dir, TOKENS_FILE)
            return model.exists() && model.length() in 200_000_000L..300_000_000L &&
                tokens.exists() && tokens.length() > 0L
        }
    }
}
