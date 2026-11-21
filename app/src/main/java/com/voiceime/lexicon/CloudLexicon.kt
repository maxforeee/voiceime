package com.voiceime.lexicon

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Cloud lexicon loader (v1.3.0).
 *
 * The lexicons live in the repo under `lexicons/` (16 files, 3,063,313
 * entries, see lexicons/README.md and lexicons/manifest.json). When the
 * phone is online, this loader fetches manifest.json from GitHub Raw,
 * downloads the TSVs, and builds `cloud_lexicon.db` in the app's files
 * dir. When offline (or on any failure) the built-in core lexicon in
 * assets/ keeps working; the cloud data is a strict addition, never a
 * requirement.
 *
 * Rules:
 * - Load order: common -> frequency -> proper/* (the collector's README
 *   order). A word already in the built-in lexicon is NOT stored again
 *   (dedupe by word+pinyin); the cloud DB only holds the gap.
 * - rime_ice.tsv (48 MB) downloads only on Wi-Fi, and only its head is
 *   read: the file is sorted by frequency descending (manifest guarantee),
 *   so reading stops at the first line below RIME_MIN_FREQ (1000). The
 *   long tail below that is words nobody types; single characters from
 *   the built-in lexicon cover them.
 * - Frequency lists have no pinyin, so they cannot add words; they only
 *   boost the frequency of cloud words (take the highest of rime freq,
 *   jieba count, 10^zipf).
 * - english_words_alpha.tsv is NOT downloaded: the built-in tier-2 asset
 *   is built from the same file.
 * - The manifest's "generated" date is stored in prefs. An unchanged
 *   manifest + an existing cloud DB means no download at all.
 * - Everything runs on a background thread; lookups never wait for it.
 *   The cloud DB is swapped in atomically (build to a temp file, rename).
 */
class CloudLexicon(
    private val context: Context,
    private val lexicon: Lexicon,
    private val english: EnglishLexicon,
) {

    enum class SyncResult { READY, ALREADY_READY, OFFLINE, PARTIAL, FAILED }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var cloudDb: SQLiteDatabase? = null
        private set

    /** Start a sync on a background thread; onDone fires on the main thread. */
    fun sync(onDone: (SyncResult) -> Unit) {
        Thread {
            val result = try {
                doSync()
            } catch (e: Exception) {
                SyncResult.FAILED
            }
            mainHandler.post { onDone(result) }
        }.start()
    }

    private fun doSync(): SyncResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedGenerated = prefs.getString(KEY_GENERATED, null)
        val dbFile = File(context.filesDir, DB_NAME)
        // Fast path: same manifest, DB already built — just open it.
        if (cloudDb == null && dbFile.exists() && storedGenerated != null) {
            // The manifest still needs a check (it may have changed), but
            // opening the existing DB first keeps lookups working while the
            // manifest is fetched below.
            openDb(dbFile)
        }
        if (!networkAvailable()) {
            return if (ready) SyncResult.ALREADY_READY else SyncResult.OFFLINE
        }
        val manifest = fetchManifest() ?: return if (ready) SyncResult.ALREADY_READY else SyncResult.FAILED
        val generated = manifest.optString("generated", "")
        if (generated.isNotEmpty() && generated == storedGenerated && ready) {
            return SyncResult.ALREADY_READY
        }
        // Build a fresh cloud DB from the manifest's entries.
        val entries = parseEntries(manifest)
        if (entries.isEmpty()) return if (ready) SyncResult.ALREADY_READY else SyncResult.FAILED
        val tmpDb = File(context.filesDir, DB_NAME + ".tmp")
        if (tmpDb.exists()) tmpDb.delete()
        var okCount = 0
        var failCount = 0
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openOrCreateDatabase(tmpDb, null)
            db.execSQL(
                "CREATE TABLE cloud_word(word TEXT NOT NULL, pinyin TEXT NOT NULL, " +
                    "freq INTEGER NOT NULL, PRIMARY KEY(word, pinyin))"
            )
            db.execSQL("CREATE INDEX idx_cloud_pinyin ON cloud_word(pinyin)")
            db.execSQL("CREATE TABLE en_word(word TEXT PRIMARY KEY, zipf REAL NOT NULL)")
            db.execSQL("CREATE TABLE en_zipf(word TEXT PRIMARY KEY, zipf REAL NOT NULL)")
            db.execSQL("CREATE TABLE freq_boost(word TEXT PRIMARY KEY, freq INTEGER NOT NULL)")
            val builtInPairs = lexicon.wordPinyinPairs()
            val builtInEn = english.tier1Words()
            // Small files first (proper nouns, top10000, frequency lists),
            // the 48 MB rime_ice last and Wi-Fi only.
            val ordered = entries.sortedBy { priority(it) }
            for (entry in ordered) {
                if (entry.id == "english_words_alpha") continue // built-in already has it
                if (entry.id == "rime_ice" && !isWifi()) {
                    failCount++
                    continue
                }
                val file = download(entry) ?: run { failCount++; continue }
                try {
                    parseIntoDb(db, entry, file, builtInPairs, builtInEn)
                    okCount++
                } catch (e: Exception) {
                    failCount++
                } finally {
                    file.delete()
                }
            }
            applyFreqBoost(db)
            db.close()
            db = null
            if (okCount == 0) {
                tmpDb.delete()
                return if (ready) SyncResult.ALREADY_READY else SyncResult.FAILED
            }
            if (dbFile.exists()) dbFile.delete()
            tmpDb.renameTo(dbFile)
            prefs.edit().putString(KEY_GENERATED, generated).apply()
            openDb(dbFile)
            pushEnglishCloudData()
            return if (failCount == 0) SyncResult.READY else SyncResult.PARTIAL
        } finally {
            try { db?.close() } catch (e: Exception) { }
            if (tmpDb.exists()) tmpDb.delete()
        }
    }

    /** Open the built cloud DB and hand it to the Chinese lexicon. */
    private fun openDb(dbFile: File) {
        try {
            val db = SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READONLY
            )
            cloudDb?.close()
            cloudDb = db
            lexicon.attachCloudDb(db)
            ready = true
        } catch (e: Exception) {
            ready = false
        }
    }

    /** Push the cloud English words + Zipf ranks into the English engine. */
    private fun pushEnglishCloudData() {
        val db = cloudDb ?: return
        try {
            val words = ArrayList<String>()
            db.rawQuery("SELECT word FROM en_word ORDER BY zipf DESC", null).use { c ->
                while (c.moveToNext()) words.add(c.getString(0))
            }
            if (words.isNotEmpty()) english.addCloudWords(words)
            val zipf = HashMap<String, Float>()
            db.rawQuery("SELECT word, zipf FROM en_zipf", null).use { c ->
                while (c.moveToNext()) zipf[c.getString(0)] = c.getFloat(1)
            }
            if (zipf.isNotEmpty()) english.setRareZipf(zipf)
        } catch (e: Exception) {
            // English cloud data is optional; the built-in lists stay.
        }
    }

    private fun fetchManifest(): JSONObject? {
        val request = Request.Builder().url(MANIFEST_URL).get().build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                JSONObject(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseEntries(manifest: JSONObject): List<ManifestEntry> {
        val out = ArrayList<ManifestEntry>()
        val arr = manifest.optJSONArray("entries") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                ManifestEntry(
                    id = o.optString("id"),
                    language = o.optString("language"),
                    format = o.optString("format"),
                    path = o.optString("path"),
                    sizeBytes = o.optLong("size_bytes"),
                )
            )
        }
        return out
    }

    /** Download order: proper nouns and small lists first, rime_ice last. */
    private fun priority(e: ManifestEntry): Int = when (e.id) {
        "ai_companies_zh", "companies_zh", "places_zh", "surnames_zh",
        "ai_companies_en", "companies_en", "places_en",
        "first_names_en", "last_names_en" -> 0
        "wordfreq_en_top10000" -> 1
        "jieba_zh", "wordfreq_zh", "wordfreq_en" -> 2
        "luna_pinyin" -> 3
        "rime_ice" -> 4
        else -> 5
    }

    private fun download(entry: ManifestEntry): File? {
        val url = RAW_BASE + entry.path
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val file = File(context.cacheDir, "lex_" + entry.id + ".tsv")
                FileOutputStream(file).use { out ->
                    body.byteStream().use { input -> input.copyTo(out) }
                }
                if (entry.sizeBytes > 0 && file.length() != entry.sizeBytes) {
                    // Size mismatch: the file changed mid-download; drop it.
                    file.delete()
                    return null
                }
                file
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseIntoDb(
        db: SQLiteDatabase,
        entry: ManifestEntry,
        file: File,
        builtInPairs: Set<String>,
        builtInEn: Set<String>,
    ) {
        when (entry.format) {
            "word\tpinyin\tfreq" -> parseZhCommon(db, entry, file, builtInPairs)
            "word\tfreq_count" -> parseFreqCount(db, file)
            // Zipf lists: Chinese ones only boost frequencies (no pinyin);
            // English ones feed the English engine (top10000 / large list).
            "word\tzipf_est" -> if (entry.language == "zh") parseZipfBoost(db, file)
            else parseZipf(db, entry, file, builtInEn)
            "name" -> parseProper(db, entry, file, builtInPairs, builtInEn)
            // "word" (english_words_alpha) is skipped by the caller.
            else -> Unit
        }
    }

    /** Chinese common lexicons: word + pinyin + freq. */
    private fun parseZhCommon(
        db: SQLiteDatabase,
        entry: ManifestEntry,
        file: File,
        builtInPairs: Set<String>,
    ) {
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO cloud_word(word, pinyin, freq) VALUES(?,?,?)"
        )
        db.beginTransaction()
        try {
            var count = 0
            BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split('\t')
                    if (parts.size < 3) continue
                    val word = parts[0]
                    val pinyin = parts[1].trim()
                    val freq = parts[2].toLongOrNull() ?: 0L
                    if (entry.id == "rime_ice") {
                        // rime_ice is sorted by frequency descending, so
                        // once it drops below the threshold, nothing after
                        // it can pass either: stop reading the 48 MB file.
                        if (freq < RIME_MIN_FREQ) break
                    } else {
                        // luna: keep words with a real frequency, or single
                        // characters (char coverage); drop the rest.
                        if (freq == 0L && word.length != 1) continue
                    }
                    if (word.isEmpty() || pinyin.isEmpty()) continue
                    if ("$word|$pinyin" in builtInPairs) continue
                    if (!pinyin.split(' ').all { it in lexicon.syllables() }) continue
                    stmt.bindString(1, word)
                    stmt.bindString(2, pinyin)
                    stmt.bindLong(3, freq)
                    stmt.executeInsert()
                    if (++count % BATCH == 0) {
                        db.setTransactionSuccessful()
                        db.endTransaction()
                        db.beginTransaction()
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            try { db.endTransaction() } catch (e: Exception) { }
        }
    }

    /** Chinese frequency lists (no pinyin): only boost frequencies. */
    private fun parseFreqCount(db: SQLiteDatabase, file: File) {
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO freq_boost(word, freq) VALUES(?,?)"
        )
        db.beginTransaction()
        try {
            BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split('\t')
                    if (parts.size < 2) continue
                    val freq = parts[1].toLongOrNull() ?: continue
                    if (parts[0].isEmpty() || freq <= 0) continue
                    stmt.bindString(1, parts[0])
                    stmt.bindLong(2, freq)
                    stmt.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            try { db.endTransaction() } catch (e: Exception) { }
        }
    }

    /** Chinese Zipf list (wordfreq_zh): no pinyin, so it only boosts
     *  frequencies — 10^zipf as a pseudo-count, same scale as jieba. */
    private fun parseZipfBoost(db: SQLiteDatabase, file: File) {
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO freq_boost(word, freq) VALUES(?,?)"
        )
        db.beginTransaction()
        try {
            BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split('\t')
                    if (parts.size < 2) continue
                    val zipf = parts[1].toFloatOrNull() ?: continue
                    if (parts[0].isEmpty() || zipf <= 0) continue
                    stmt.bindString(1, parts[0])
                    stmt.bindLong(2, 10.0.pow(zipf.toDouble()).toLong())
                    stmt.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            try { db.endTransaction() } catch (e: Exception) { }
        }
    }

    /** English frequency lists: top10000 -> en_word, large list -> en_zipf. */
    private fun parseZipf(
        db: SQLiteDatabase,
        entry: ManifestEntry,
        file: File,
        builtInEn: Set<String>,
    ) {
        val isTop = entry.id == "wordfreq_en_top10000"
        val stmt = db.compileStatement(
            if (isTop) "INSERT OR REPLACE INTO en_word(word, zipf) VALUES(?,?)"
            else "INSERT OR REPLACE INTO en_zipf(word, zipf) VALUES(?,?)"
        )
        db.beginTransaction()
        try {
            BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split('\t')
                    if (parts.size < 2) continue
                    val word = parts[0].trim().lowercase()
                    val zipf = parts[1].toFloatOrNull() ?: continue
                    if (word.isEmpty() || !word.all { it in 'a'..'z' }) continue
                    if (isTop && word in builtInEn) continue
                    stmt.bindString(1, word)
                    stmt.bindDouble(2, zipf.toDouble())
                    stmt.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            try { db.endTransaction() } catch (e: Exception) { }
        }
    }

    /** Proper nouns: English names -> en_word; Chinese names -> cloud_word. */
    private fun parseProper(
        db: SQLiteDatabase,
        entry: ManifestEntry,
        file: File,
        builtInPairs: Set<String>,
        builtInEn: Set<String>,
    ) {
        if (entry.language == "en") {
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO en_word(word, zipf) VALUES(?,?)"
            )
            db.beginTransaction()
            try {
                BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val name = line!!.trim().lowercase()
                        if (name.isEmpty() || !name.all { it in 'a'..'z' }) continue
                        if (name in builtInEn) continue
                        stmt.bindString(1, name)
                        stmt.bindDouble(2, PROPER_ZIPF.toDouble())
                        stmt.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                try { db.endTransaction() } catch (e: Exception) { }
            }
            return
        }
        // Chinese names: generate pinyin per character (first reading).
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO cloud_word(word, pinyin, freq) VALUES(?,?,?)"
        )
        db.beginTransaction()
        try {
            BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val name = line!!.trim()
                    if (name.isEmpty()) continue
                    val syls = ArrayList<String>()
                    var ok = true
                    for (ch in name) {
                        val py = lexicon.charPinyin(ch)
                        if (py == null || py !in lexicon.syllables()) {
                            ok = false
                            break
                        }
                        syls.add(py)
                    }
                    if (!ok) continue
                    val pinyin = syls.joinToString(" ")
                    if ("$name|$pinyin" in builtInPairs) continue
                    stmt.bindString(1, name)
                    stmt.bindString(2, pinyin)
                    stmt.bindLong(3, PROPER_FREQ)
                    stmt.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            try { db.endTransaction() } catch (e: Exception) { }
        }
    }

    /** Frequency lists boost cloud words: keep the highest frequency. */
    private fun applyFreqBoost(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            // jieba counts are real counts; wordfreq Zipf is converted with
            // 10^zipf so the two scales are comparable (both are log-scale
            // frequencies, and the ranking only ever compares their logs).
            db.execSQL(
                "UPDATE cloud_word SET freq = MAX(freq, " +
                    "(SELECT freq FROM freq_boost WHERE freq_boost.word = cloud_word.word)) " +
                    "WHERE EXISTS (SELECT 1 FROM freq_boost WHERE freq_boost.word = cloud_word.word)"
            )
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            // Boost is optional; the cloud words keep their source frequency.
        } finally {
            try { db.endTransaction() } catch (e: Exception) { }
            try { db.execSQL("DROP TABLE IF EXISTS freq_boost") } catch (e: Exception) { }
        }
    }

    private fun networkAvailable(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private data class ManifestEntry(
        val id: String,
        val language: String,
        val format: String,
        val path: String,
        val sizeBytes: Long,
    )

    companion object {
        private const val MANIFEST_URL =
            "https://raw.githubusercontent.com/maxforeee/voiceime/main/lexicons/manifest.json"
        private const val RAW_BASE =
            "https://raw.githubusercontent.com/maxforeee/voiceime/main/lexicons/"
        private const val PREFS = "voiceime_cloud"
        private const val KEY_GENERATED = "manifest_generated"
        private const val DB_NAME = "cloud_lexicon.db"
        private const val BATCH = 5000
        /** Cloud rime_ice only keeps words this frequent or more. */
        private const val RIME_MIN_FREQ = 1000L
        /** Built-in proper-noun corpus count (medium-common tier). */
        private const val PROPER_FREQ = 2000L
        /** Proper nouns' Zipf rank in the English cloud list. */
        private const val PROPER_ZIPF = 3.5f
    }
}
