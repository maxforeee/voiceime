package com.voiceime.asr

import com.voiceime.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Backend calls (deepseek-flash on VPS) for the voice flow.
 * - correct(): semantic correction of ASR final text.
 * - modify(): apply a spoken instruction to the text already in the field.
 * POST {"text": ...} -> {"ok": true, "corrected": "..."}.
 * POST {"text": ..., "instruction": ...} -> {"ok": true, "modified": "..."}.
 * The backend holds the model key; the app sends only the shared token
 * (BuildConfig.CORRECT_TOKEN, from the repo secret). Any failure / timeout /
 * empty result yields null so the caller keeps the original text instead.
 *
 * Like DoubaoAsrClient, callbacks fire on OkHttp's worker thread; the caller
 * must post to the main thread and guard against stale sessions.
 */
class SemanticCorrector {

    private val client = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var pending: Call? = null
    private var typoPending: Call? = null

    /** onResult is invoked exactly once, with the corrected text or null.
     *  level selects the backend cleaning template (normal / medium /
     *  strong); modify() is unaffected by it. */
    fun correct(text: String, level: String, onResult: (corrected: String?) -> Unit) {
        cancel()
        if (text.isEmpty()) {
            onResult(null)
            return
        }
        val body = JSONObject().put("text", text).put("level", level).toString()
        post(CORRECT_URL, body, "corrected", onResult)
    }

    /** onResult is invoked exactly once, with the answer or null.
     *  Alternative to correct() for questions / arithmetic problems; the
     *  two never run concurrently, so they share post()'s pending slot. */
    fun ask(text: String, onResult: (answer: String?) -> Unit) {
        cancel()
        if (text.isEmpty()) {
            onResult(null)
            return
        }
        val body = JSONObject().put("text", text).toString()
        post(ASK_URL, body, "answer", onResult)
    }

    /** onResult is invoked exactly once, with the modified text or null. */
    fun modify(original: String, instruction: String, onResult: (modified: String?) -> Unit) {
        cancel()
        if (original.isEmpty() || instruction.isEmpty()) {
            onResult(null)
            return
        }
        val body = JSONObject().put("text", original).put("instruction", instruction).toString()
        post(MODIFY_URL, body, "modified", onResult)
    }

    /**
     * Typo check (v1.3.0): judge one suspect word in its sentence context.
     * [mode] is "pinyin" / "english" / "word" (pending pinyin, pending
     * English, or a just-committed word). onResult is invoked exactly once,
     * with the corrected word, or null when the word is fine / unsure / any
     * failure. The caller shows the word as an AI candidate; it never
     * replaces anything by itself. Runs on its own call so it never
     * cancels a voice correction in flight (and vice versa).
     */
    fun correctTypo(text: String, suspect: String, digits: String?, mode: String, onResult: (correctedWord: String?) -> Unit) {
        cancelTypo()
        if (text.isEmpty() || suspect.isEmpty()) {
            onResult(null)
            return
        }
        val body = JSONObject().put("text", text).put("suspect", suspect).put("mode", mode)
        if (digits != null) body.put("digits", digits)
        postTypo(TYPO_URL, body.toString(), "word", onResult)
    }

    private fun postTypo(url: String, bodyJson: String, resultKey: String, onResult: (String?) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("X-Correct-Token", BuildConfig.CORRECT_TOKEN)
            .post(bodyJson.toRequestBody(JSON_TYPE))
            .build()
        val call = client.newCall(request)
        typoPending = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (typoPending !== call) return
                // Timeout / cancel / no network: caller stays local-only.
                typoPending = null
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (typoPending !== call) return
                val out = response.use { r ->
                    if (!r.isSuccessful) return@use null
                    parse(r.body?.string() ?: return@use null, resultKey)
                }
                if (typoPending === call) typoPending = null
                onResult(out)
            }
        })
    }

    fun cancelTypo() {
        typoPending?.cancel()
        typoPending = null
    }

    private fun post(url: String, bodyJson: String, resultKey: String, onResult: (String?) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("X-Correct-Token", BuildConfig.CORRECT_TOKEN)
            .post(bodyJson.toRequestBody(JSON_TYPE))
            .build()
        val call = client.newCall(request)
        pending = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (pending !== call) return
                // Timeout / cancel / no network: caller falls back to original.
                pending = null
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (pending !== call) return
                val out = response.use { r ->
                    if (!r.isSuccessful) return@use null
                    parse(r.body?.string() ?: return@use null, resultKey)
                }
                if (pending === call) pending = null
                onResult(out)
            }
        })
    }

    fun cancel() {
        pending?.cancel()
        pending = null
        cancelTypo()
    }

    private fun parse(body: String, key: String): String? {
        return try {
            val obj = JSONObject(body)
            if (!obj.optBoolean("ok", false)) return null
            obj.optString(key, "").trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val CORRECT_URL = "https://toolseverything.com:442/voiceime-correct/correct"
        private const val MODIFY_URL = "https://toolseverything.com:442/voiceime-correct/modify"
        private const val TYPO_URL = "https://toolseverything.com:442/voiceime-correct/typo"
        private const val ASK_URL = "https://toolseverything.com:442/voiceime-correct/ask"
        private const val TIMEOUT_S = 15L
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Pure detector: true when the text is a question or an arithmetic
         *  problem (routed to ask() instead of correct()). */
        fun isQuestion(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            if (t.endsWith("？") || t.endsWith("?")) return true
            val markers = listOf(
                "什么意思", "怎么", "为什么", "多少", "什么", "哪里", "哪个",
                "哪些", "谁", "何时", "什么时候", "多久", "是不是", "吗",
                "能否", "可以吗", "对吗"
            )
            if (markers.any { t.contains(it) }) return true
            val operators = listOf("+", "-", "×", "x", "*", "/", "÷", "加", "减", "乘", "除")
            if (t.any { it.isDigit() } && operators.any { t.contains(it) } &&
                (t.contains("等于") || t.contains("多少") || t.contains("是"))
            ) return true
            val lower = t.lowercase()
            val prefixes = listOf(
                "what ", "how ", "why ", "when ", "where ", "who ", "whom ",
                "whose ", "which ", "is ", "are ", "can ", "could ", "do ",
                "does ", "did "
            )
            return prefixes.any { lower.startsWith(it) }
        }
    }
}
