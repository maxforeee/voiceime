package com.voiceime.asr

import android.content.Context
import com.voiceime.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads the Sherpa-ONNX Paraformer offline pack on user tap only.
 *
 * Source: GitHub Releases asset sherpa-onnx-paraformer-trilingual-zh-cantonese-en.tar.bz2
 * (tag asr-models), domestic mirror hub.nuaa.cf on GitHub failure.
 *
 * The archive is ~1.0 GB (it also carries the 831 MB fp32 model and test
 * wavs); only model.int8.onnx (~234 MB) and tokens.txt are extracted, the
 * archive is deleted right after. ~1.5 GB free space is required up front.
 * Progress is reported as 0-100 percent of the archive size.
 */
class OfflineModelDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Runs on a worker thread. onProgress fires on the download thread;
     * onDone fires on the download thread with (success, error string res).
     */
    fun download(onProgress: (Int) -> Unit, onDone: (success: Boolean, errorRes: Int) -> Unit) {
        Thread {
            val errorRes = runDownload(onProgress)
            onDone(errorRes == 0, errorRes)
        }.start()
    }

    private fun runDownload(onProgress: (Int) -> Unit): Int {
        val dir = OfflineAsr.modelDir(context)
        dir.mkdirs()
        // Archive (~1.06 GB) + extracted int8 model (~234 MB) coexist briefly.
        if (dir.freeSpace < 1_500_000_000L) return R.string.settings_offline_no_space
        val archive = File(dir, "model.tar.bz2")
        try {
            var downloaded = false
            for (url in URLS) {
                if (downloadUrl(url, archive, onProgress)) {
                    downloaded = true
                    break
                }
                archive.delete()
            }
            if (!downloaded || archive.length() != EXPECTED_ARCHIVE_SIZE) {
                archive.delete()
                return R.string.settings_offline_failed
            }
            extract(archive, dir)
            archive.delete()
            return if (OfflineAsr.isModelReady(context)) 0
            else R.string.settings_offline_failed
        } catch (e: Exception) {
            archive.delete()
            File(dir, OfflineAsr.MODEL_FILE).delete()
            File(dir, OfflineAsr.TOKENS_FILE).delete()
            return R.string.settings_offline_failed
        }
    }

    /** Stream one URL to the archive file; false on any failure. */
    private fun downloadUrl(url: String, out: File, onProgress: (Int) -> Unit): Boolean {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                var done = 0L
                var lastPct = -1
                body.byteStream().use { input ->
                    FileOutputStream(out).use { output ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            val pct = ((done * 100) / EXPECTED_ARCHIVE_SIZE)
                                .toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
                done > 0L
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Extract only model.int8.onnx + tokens.txt; skip the fp32 model and wavs. */
    private fun extract(archive: File, dir: File) {
        BZip2CompressorInputStream(FileInputStream(archive).buffered()).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.substringAfterLast('/')
                        if (name == OfflineAsr.MODEL_FILE || name == OfflineAsr.TOKENS_FILE) {
                            File(dir, name).outputStream().use { out ->
                                tar.copyTo(out)
                            }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        val model = File(dir, OfflineAsr.MODEL_FILE)
        val tokens = File(dir, OfflineAsr.TOKENS_FILE)
        if (!model.exists() || model.length() !in 200_000_000L..300_000_000L ||
            !tokens.exists() || tokens.length() == 0L
        ) {
            throw IOException("incomplete offline model")
        }
    }

    companion object {
        private const val ASSET =
            "sherpa-onnx-paraformer-trilingual-zh-cantonese-en.tar.bz2"
        private const val EXPECTED_ARCHIVE_SIZE = 1_059_453_702L
        private val URLS = listOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$ASSET",
            "https://hub.nuaa.cf/k2-fsa/sherpa-onnx/releases/download/asr-models/$ASSET"
        )
    }
}
