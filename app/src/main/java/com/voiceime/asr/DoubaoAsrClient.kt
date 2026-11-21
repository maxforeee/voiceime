package com.voiceime.asr

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Doubao streaming ASR over WebSocket (M1-verified endpoint + protocol).
 * The API key comes ONLY from BuildConfig (injected at build time from env
 * DOUBAO_API_KEY_FROM_CONFIG); it is never logged or written anywhere.
 *
 * All listener callbacks fire on OkHttp's reader thread; the caller must
 * marshal them to the main thread.
 */
class DoubaoAsrClient(private val apiKey: String, private val listener: Listener) {

    interface Listener {
        fun onReady()
        fun onInterim(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onClosed()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var seq = 2
    @Volatile private var stopped = false
    private var finalReceived = false

    val gotFinal: Boolean get() = finalReceived

    fun connect() {
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("X-Api-Key", apiKey)
            .header("X-Api-Resource-Id", RESOURCE_ID)
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val payload = JSONObject().apply {
                    put("user", JSONObject().put("uid", "voiceime_android"))
                    put("audio", JSONObject().apply {
                        put("format", "pcm")
                        put("codec", "raw")
                        put("rate", 16000)
                        put("bits", 16)
                        put("channel", 1)
                    })
                    put("request", JSONObject().apply {
                        put("model_name", "bigmodel")
                        put("enable_punc", true)
                        put("enable_itn", false)
                        put("show_utterances", true)
                        put("result_type", "full")
                    })
                }
                webSocket.send(AsrProtocol.fullClientRequest(payload.toString()).toByteString())
                listener.onReady()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val r = AsrProtocol.parseResponse(bytes.toByteArray()) ?: return
                if (r.errorCode != null) {
                    listener.onError("识别失败（错误码 ${r.errorCode}）")
                    return
                }
                if (r.isLast) {
                    finalReceived = true
                    listener.onFinal(r.text ?: "")
                } else if (!r.text.isNullOrEmpty()) {
                    listener.onInterim(r.text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stopped) return
                val msg = when {
                    response != null && response.code in 401..403 ->
                        "语音服务鉴权失败，请检查 Key 配置"
                    t is UnknownHostException -> "网络不可用，无法连接语音服务"
                    t is java.net.SocketTimeoutException -> "语音服务连接超时"
                    else -> "语音识别连接失败"
                }
                listener.onError(msg)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed()
            }
        })
    }

    /** Called from the audio capture thread; OkHttp send is thread-safe. */
    fun sendAudio(pcm: ByteArray, length: Int, last: Boolean) {
        val w = ws ?: return
        if (stopped && !last) return
        w.send(AsrProtocol.audioPacket(pcm, length, seq, last).toByteString())
        seq++
    }

    /** Graceful close after the final packet was sent. */
    fun finish() {
        stopped = true
        ws?.close(1000, "done")
        ws = null
    }

    /** Immediate abort (mode switch, input finished, error). */
    fun cancel() {
        stopped = true
        ws?.cancel()
        ws = null
    }

    companion object {
        private const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        private const val RESOURCE_ID = "volc.seedasr.sauc.duration"
    }
}
