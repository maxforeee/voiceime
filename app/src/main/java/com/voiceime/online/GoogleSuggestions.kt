package com.voiceime.online

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google Input Tools suggestions (UNOFFICIAL endpoint, no key, no SLA).
 * Verified in M2: 14/14 rounds SUCCESS, 0.11-0.23s latency, partial pinyin
 * works ("nih" -> 你好). Hard rules from the spec:
 *  - caller debounces ~100ms after a keypress;
 *  - the whole call is dropped after 300ms (callTimeout);
 *  - only the pinyin string is ever sent, never committed sentences;
 *  - failures are silent: local candidates are already on screen and the
 *    keyboard never waits for the network.
 */
class GoogleSuggestions(private val onResult: (digits: String, words: List<String>) -> Unit) {

    private val client = OkHttpClient.Builder()
        .callTimeout(300, TimeUnit.MILLISECONDS)
        .connectTimeout(300, TimeUnit.MILLISECONDS)
        .readTimeout(300, TimeUnit.MILLISECONDS)
        .writeTimeout(300, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var pending: Call? = null

    fun request(digits: String, pinyin: String) {
        cancel()
        if (pinyin.isEmpty()) return
        val url = "https://inputtools.google.com/request?text=" +
            URLEncoder.encode(pinyin, "UTF-8") +
            "&itc=zh-t-i0-pinyin&num=10&cp=0&cs=1&ie=utf-8&oe=utf-8&app=jsapi"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "VoiceIME/0.1")
            .build()
        val call = client.newCall(request)
        pending = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Timeout / cancel / no network: drop silently, local stays.
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) return
                    val body = r.body?.string() ?: return
                    val words = parse(body)
                    if (words.isNotEmpty()) onResult(digits, words)
                }
            }
        })
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }

    /** Response shape: SUCCESS with groups of (input, candidates). */
    private fun parse(body: String): List<String> {
        return try {
            val arr = JSONArray(body)
            if (arr.optString(0) != "SUCCESS") return emptyList()
            val groups = arr.optJSONArray(1) ?: return emptyList()
            val first = groups.optJSONArray(0) ?: return emptyList()
            val cands = first.optJSONArray(1) ?: return emptyList()
            val out = ArrayList<String>(cands.length())
            for (i in 0 until cands.length()) {
                val w = cands.optString(i)
                if (w.isNotEmpty()) out.add(w)
            }
            out
        } catch (e: Exception) {
            Log.d(TAG, "unexpected response shape")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "VoiceImeOnline"
    }
}
