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
 * Backend semantic correction of ASR final text (deepseek-flash on VPS).
 * POST {"text": ...} -> {"ok": true, "corrected": "..."}. The backend holds
 * the model key; the app sends no credentials. Any failure / timeout /
 * empty result yields null so the caller commits the original text instead.
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
            .toRequestBody(JSON_TYPE)
        val request = Request.Builder()
            .url(CORRECT_URL)
            .header("X-Correct-Token", BuildConfig.CORRECT_TOKEN)
            .post(body)
            .build()
        val call = client.newCall(request)
        pending = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Timeout / cancel / no network: caller falls back to original.
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val corrected = response.use { r ->
                    if (!r.isSuccessful) return@use null
                    parse(r.body?.string() ?: return@use null)
                }
                onResult(corrected)
            }
        })
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }

    private fun parse(body: String): String? {
        return try {
            val obj = JSONObject(body)
            if (!obj.optBoolean("ok", false)) return null
            obj.optString("corrected", "").trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val CORRECT_URL = "https://toolseverything.com:442/voiceime-correct/correct"
        private const val TIMEOUT_S = 15L
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
