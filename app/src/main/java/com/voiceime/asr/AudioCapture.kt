package com.voiceime.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Microphone capture: 16 kHz, 16-bit, mono PCM, streamed in 100 ms chunks
 * (3200 bytes) on a dedicated thread. The blocking AudioRecord.read() paces
 * the stream at real time, matching the M1 harness.
 * Permission must be granted before start() (checked by the caller).
 */
class AudioCapture(
    private val onChunk: (pcm: ByteArray, length: Int) -> Unit,
    private val onError: (String) -> Unit
) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                onError("录音不可用")
                return false
            }
            val r = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, CHUNK_BYTES * 4)
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                onError("录音初始化失败")
                return false
            }
            record = r
            r.startRecording()
            running = true
            thread = Thread {
                val buf = ByteArray(CHUNK_BYTES)
                while (running) {
                    val n = try {
                        r.read(buf, 0, buf.size)
                    } catch (e: Exception) {
                        -1
                    }
                    if (n > 0) {
                        onChunk(buf, n)
                    } else if (n < 0) {
                        onError("录音读取失败")
                        break
                    }
                }
            }.apply { start() }
            true
        } catch (e: SecurityException) {
            onError("没有麦克风权限")
            false
        } catch (e: Exception) {
            onError("录音启动失败")
            false
        }
    }

    /** Stops the capture thread and releases the mic. Caller then sends the last packet. */
    fun stop() {
        running = false
        thread?.let {
            try {
                it.join(600)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
        try {
            record?.stop()
        } catch (e: Exception) {
            // already stopped
        }
        try {
            record?.release()
        } catch (e: Exception) {
            // already released
        }
        record = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_BYTES = SAMPLE_RATE * 2 * 100 / 1000  // 100 ms of 16-bit mono
    }
}
