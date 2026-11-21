package com.voiceime.asr

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/**
 * Volcano v3 binary WebSocket protocol for Doubao streaming ASR.
 * Exact Kotlin port of the M1-verified harness (m1_verify/run_m1.py):
 * 4-byte header, full client request (JSON), audio-only packets with
 * big-endian sequence (negative on the last packet), gzip-capable responses.
 */
object AsrProtocol {

    data class Response(
        val seq: Int?,
        val isLast: Boolean,
        val errorCode: Int?,
        val errorMessage: String?,
        val text: String?
    )

    private fun header(msgType: Int, flags: Int, serial: Int, compress: Int): ByteArray =
        byteArrayOf(
            0x11,
            ((msgType shl 4) or flags).toByte(),
            ((serial shl 4) or compress).toByte(),
            0x00
        )

    fun fullClientRequest(payloadJson: String, seq: Int = 1): ByteArray {
        val body = payloadJson.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(12 + body.size)
        buf.put(header(1, 1, 1, 0))
        buf.putInt(seq)
        buf.putInt(body.size)
        buf.put(body)
        return buf.array()
    }

    fun audioPacket(pcm: ByteArray, length: Int, seq: Int, last: Boolean): ByteArray {
        val flags = if (last) 3 else 1
        val s = if (last) -seq else seq
        val buf = ByteBuffer.allocate(12 + length)
        buf.put(header(2, flags, 0, 0))
        buf.putInt(s)
        buf.putInt(length)
        buf.put(pcm, 0, length)
        return buf.array()
    }

    fun parseResponse(data: ByteArray): Response? {
        if (data.size < 4) return null
        val msgType = (data[1].toInt() and 0xFF) ushr 4
        val flags = data[1].toInt() and 0x0F
        val serial = (data[2].toInt() and 0xFF) ushr 4
        val compress = data[2].toInt() and 0x0F
        var off = 4
        var seq: Int? = null
        var code: Int? = null
        if (msgType == 0x0F) {
            if (data.size < off + 4) return null
            code = ByteBuffer.wrap(data, off, 4).int
            off += 4
        } else if (flags and 0x01 != 0) {
            if (data.size < off + 4) return null
            seq = ByteBuffer.wrap(data, off, 4).int
            off += 4
        }
        if (data.size < off + 4) return null
        val size = ByteBuffer.wrap(data, off, 4).int
        off += 4
        if (size < 0 || data.size < off + size) return null
        var payload = data.copyOfRange(off, off + size)
        if (compress == 1) {
            payload = try {
                GZIPInputStream(ByteArrayInputStream(payload)).use { gz ->
                    val out = ByteArrayOutputStream()
                    gz.copyTo(out)
                    out.toByteArray()
                }
            } catch (e: Exception) {
                return null
            }
        }
        if (msgType == 0x0F) {
            return Response(seq, false, code, String(payload, Charsets.UTF_8), null)
        }
        var text: String? = null
        if (serial == 1) {
            try {
                val json = JSONObject(String(payload, Charsets.UTF_8))
                text = json.optJSONObject("result")?.optString("text", null)
            } catch (e: Exception) {
                // Non-JSON server payload: nothing to display.
            }
        }
        return Response(seq, flags and 0x02 != 0, null, null, text)
    }
}
