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

    /** onResult is invoked exactly once, with the corrected text or null. */
    fun correct(text: String, onResult: (corrected: String?) -> Unit) {
        cancel()
        if (text.isEmpty()) {
            onResult(null)
            return
        }
        val body = JSONObject().put("text", text).toString()
        post(CORRECT_URL, body, "corrected", onResult)
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
                // Timeout / cancel / no network: caller falls back to original.
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val out = response.use { r ->
                    if (!r.isSuccessful) return@use null
                    parse(r.body?.string() ?: return@use null, resultKey)
                }
                onResult(out)
            }
        })
    }

    fun cancel() {
        pending?.cancel()
        pending = null
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
        private const val TIMEOUT_S = 15L
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
