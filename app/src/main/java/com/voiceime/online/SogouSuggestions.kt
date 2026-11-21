package com.voiceime.online

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sogou cloud suggestions (UNOFFICIAL endpoint, no key, no SLA).
 * Replaces Google Input Tools, removed at the user's request.
 *
 * Protocol: binary POST to shouji.sogou.com/web_ime/mobile.php — the same
 * endpoint Sogou's own mobile IME uses (see xing133/rime-wenyun
 * lua/wenyun.lua, which re-implements it from librime-cloud).
 * Verified live 2026-11-21: 6/6 queries returned correct top words
 * (nihao -> 你好, zhongguo -> 中国, xiexie -> 谢谢, women -> 我们,
 * shijie -> 世界, nihaoma -> 你好吗). Baidu olime was tested the same day
 * and is dead ({"status":"F","errno":"19"}), so it is NOT used.
 *
 * Hard rules (same as the old Google line):
 *  - caller debounces ~100ms after a keypress;
 *  - the whole call is dropped after 300ms (callTimeout);
 *  - only the pinyin string is ever sent, never committed sentences;
 *  - failures are silent: local candidates are already on screen and the
 *    keyboard never waits for the network.
 * Note: the endpoint is plain HTTP; only the pinyin string is sent.
 */
class SogouSuggestions(private val onResult: (digits: String, words: List<String>) -> Unit) {

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
        val body = buildPayload(pinyin) ?: return
        val request = Request.Builder()
            .url(SOGOU_URL)
            .post(body.toRequestBody(BINARY_TYPE))
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
                    val bytes = r.body?.bytes() ?: return
                    val words = parse(bytes)
                    if (words.isNotEmpty()) onResult(digits, words)
                }
            }
        })
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }

    companion object {
        private const val TAG = "VoiceImeOnline"
        private const val SOGOU_URL =
            "http://shouji.sogou.com/web_ime/mobile.php?durtot=0&h=000000000000000&r=store_mf_wandoujia&v=3.7"
        private val BINARY_TYPE = "application/octet-stream".toMediaType()

        /** Payload = len .. token .. keylen .. pinyin .. xor checksum. */
        fun buildPayload(pinyin: String): ByteArray? {
            val pb = pinyin.toByteArray(Charsets.UTF_8)
            val token = byteArrayOf(0, 5, 0, 0, 0, 0, 1)
            if (pb.isEmpty() || token.size + pb.size + 3 > 255) return null
            val data = ByteArray(1 + token.size + 1 + pb.size)
            data[0] = (token.size + pb.size + 3).toByte()
            token.copyInto(data, 1)
            data[1 + token.size] = pb.size.toByte()
            pb.copyInto(data, 1 + token.size + 1)
            var xor = 0
            for (b in data) xor = xor xor (b.toInt() and 0xFF)
            return data + xor.toByte()
        }

        /** Response: count at bytes 18-19 (LE u16); entries from offset 20. */
        fun parse(bytes: ByteArray): List<String> {
            if (bytes.size < 0x16) return emptyList()
            val num = (bytes[18].toInt() and 0xFF) or ((bytes[19].toInt() and 0xFF) shl 8)
            if (num == 0 || num > 32) return emptyList()
            val out = ArrayList<String>()
            var pos = 20
            return try {
                repeat(num) {
                    if (pos + 1 >= bytes.size) return emptyList()
                    val slen = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    if (pos + slen > bytes.size) return emptyList()
                    val word = String(bytes, pos, slen, Charsets.UTF_16LE)
                    pos += slen
                    // Skip two fields (each: u16 length + bytes) plus one byte.
                    repeat(2) {
                        if (pos + 1 >= bytes.size) return emptyList()
                        val skip = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
                        pos += skip + 2
                    }
                    pos += 1
                    if (word.isNotEmpty() && out.size < 10) out.add(word)
                }
                out
            } catch (e: Exception) {
                Log.d(TAG, "unexpected response shape")
                emptyList()
            }
        }
    }
}
