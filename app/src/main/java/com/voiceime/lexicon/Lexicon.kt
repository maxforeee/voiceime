package com.voiceime.lexicon

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.voiceime.Candidate
import com.voiceime.CandidateProvider
import com.voiceime.CandidateResult
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.log10
import kotlin.math.min

/**
 * Local-first T9 lexicon engine. Mirrors the verified m2_verify/t9_verify.py
 * logic (and tools/test_lexicon.py, which checks the built DB on the Box):
 *
 *  1. DP over the 424-syllable table enumerates ALL valid segmentations (cap 200).
 *  2. Every segmentation is looked up in the prebuilt pinyin->words SQLite index
 *     (primary-key reads only, never a table scan at keypress time).
 *  3. Hits are ranked globally by (log10 corpus frequency, dict weight), deduped
 *     by word, top 50 returned for the scrollable candidate bar.
 *  4. The digit-prefix index appends partial-input suggestions (deduped).
 *  5. Tapped candidates are remembered and boosted on later lookups.
 *
 * The DB is built offline by tools/build_lexicon.py and bundled in assets.
 */
class Lexicon(private val context: Context) : CandidateProvider {

    private var db: SQLiteDatabase? = null
    private val sylByDigits = HashMap<String, List<String>>()
    private val taps = HashMap<String, Int>()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Copy the asset DB once, open it, load the syllable table + tap memory. */
    fun prepare() {
        try {
            val dbFile = File(context.filesDir, DB_NAME)
            if (!dbFile.exists()) copyAsset(dbFile)
            var database = open(dbFile)
            if (schemaVersion(database) != SCHEMA_VERSION) {
                database.close()
                copyAsset(dbFile)
                database = open(dbFile)
            }
            db = database
            sylByDigits.clear()
            database.rawQuery("SELECT d, s FROM syl", null).use { c ->
                val tmp = HashMap<String, MutableList<String>>()
                while (c.moveToNext()) {
                    tmp.getOrPut(c.getString(0)) { mutableListOf() }.add(c.getString(1))
                }
                for ((d, list) in tmp) sylByDigits[d] = list.sorted()
            }
            loadTaps()
        } catch (e: Exception) {
            // Typing must keep working even if the lexicon cannot be opened.
            Log.e(TAG, "lexicon prepare failed: ${e.javaClass.simpleName}")
            db = null
        }
    }

    override fun lookup(digits: String): CandidateResult {
        val database = db ?: return CandidateResult(digits, emptyList())
        if (digits.isEmpty()) return CandidateResult("", emptyList())
        return try {
            val segs = segment(digits)
            val pinyin = segs.firstOrNull()?.joinToString(" ") ?: digits
            val keys = segs.map { it.joinToString("") }.distinct()

            // (wordId, weight, pinyinKey) for every hit across ALL segmentations
            val entries = ArrayList<Triple<Int, Double, String>>()
            for (key in keys) entries.addAll(readKey(database, key))

            val words = readWords(database, entries.map { it.first }.distinct())
            val best = HashMap<String, Long>()
            val bestKey = HashMap<String, String>()
            for ((wid, weight, key) in entries) {
                val wf = words[wid] ?: continue
                val s = score(wf.second, weight) + boost(wf.first)
                val prev = best[wf.first]
                if (prev == null || s > prev) {
                    best[wf.first] = s
                    bestKey[wf.first] = key
                }
            }
            val ranked = best.entries.sortedByDescending { it.value }.take(TOP_N)
            val cands = ArrayList<Candidate>(ranked.size + 30)
            for (e in ranked) cands.add(Candidate(e.key, bestKey[e.key] ?: ""))

            // Partial-input suggestions from the prebuilt digit-prefix index.
            val seen = HashSet(cands.map { it.word })
            val prefixIds = ArrayList<Pair<Int, Long>>()
            database.rawQuery(
                "SELECT wid, s FROM prefix WHERE d GLOB ? ORDER BY s DESC LIMIT 30",
                arrayOf(digits + "*")
            ).use { c ->
                while (c.moveToNext()) prefixIds.add(c.getInt(0) to c.getLong(1))
            }
            val prefixWords = readWords(database, prefixIds.map { it.first })
            for ((wid, s) in prefixIds) {
                val wf = prefixWords[wid] ?: continue
                if (seen.add(wf.first)) cands.add(Candidate(wf.first, ""))
            }

            if (cands.isEmpty() && segs.isNotEmpty()) {
                // Rare phrase dropped by the build-time trim: offer the chars of
                // the first syllable so the user can build the word char by char.
                val firstSyl = segs[0][0]
                val chars = readKey(database, firstSyl)
                val charWords = readWords(database, chars.map { it.first }.distinct())
                val charBest = HashMap<String, Long>()
                for ((wid, weight, _) in chars) {
                    val wf = charWords[wid] ?: continue
                    val s = score(wf.second, weight) + boost(wf.first)
                    val prev = charBest[wf.first]
                    if (prev == null || s > prev) charBest[wf.first] = s
                }
                charBest.entries.sortedByDescending { it.value }.take(TOP_N)
                    .forEach { cands.add(Candidate(it.key, firstSyl)) }
            }
            CandidateResult(pinyin, cands)
        } catch (e: Exception) {
            Log.e(TAG, "lookup failed: ${e.javaClass.simpleName}")
            CandidateResult(digits, emptyList())
        }
    }

    override fun rememberTap(word: String) {
        taps[word] = min((taps[word] ?: 0) + 1, 100)
        if (taps.size > MAX_TAPS) {
            val coldest = taps.entries.sortedBy { it.value }.take(taps.size - MAX_TAPS)
            coldest.forEach { taps.remove(it.key) }
        }
        val json = JSONObject()
        taps.forEach { (w, n) -> json.put(w, n) }
        prefs.edit().putString(KEY_TAPS, json.toString()).apply()
    }

    // ---- segmentation (exact port of t9_verify.py segment()) ----------------

    private fun segment(digits: String, cap: Int = 200): List<List<String>> {
        val memo = HashMap<Int, List<List<String>>>()
        fun rec(i: Int): List<List<String>> {
            if (i == digits.length) return listOf(emptyList())
            memo[i]?.let { return it }
            val out = ArrayList<List<String>>()
            for (len in 1..6) {
                if (i + len > digits.length) break
                val chunk = digits.substring(i, i + len)
                val syls = sylByDigits[chunk] ?: continue
                for (s in syls) {
                    for (rest in rec(i + len)) {
                        out.add(listOf(s) + rest)
                        if (out.size >= cap) {
                            memo[i] = out
                            return out
                        }
                    }
                }
            }
            memo[i] = out
            return out
        }
        return rec(0)
    }

    // ---- DB helpers ----------------------------------------------------------

    /** Decode one pykey blob: rank-ordered (varint wordId, u16 weight*65535). */
    private fun readKey(database: SQLiteDatabase, key: String): List<Triple<Int, Double, String>> {
        val out = ArrayList<Triple<Int, Double, String>>()
        database.rawQuery("SELECT ws FROM pykey WHERE p=?", arrayOf(key)).use { c ->
            if (!c.moveToNext()) return out
            val blob = c.getBlob(0)
            var pos = 0
            while (pos < blob.size) {
                var wid = 0
                var shift = 0
                while (true) {
                    val b = blob[pos].toInt() and 0xFF
                    pos++
                    wid = wid or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                val wInt = ((blob[pos].toInt() and 0xFF) shl 8) or (blob[pos + 1].toInt() and 0xFF)
                pos += 2
                out.add(Triple(wid, wInt / 65535.0, key))
            }
        }
        return out
    }

    /** wordId -> (word, corpusCount). */
    private fun readWords(database: SQLiteDatabase, ids: List<Int>): Map<Int, Pair<String, Int>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Int, Pair<String, Int>>(ids.size)
        val placeholders = ids.joinToString(",") { "?" }
        database.rawQuery(
            "SELECT id, w, f FROM word WHERE id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) out[c.getInt(0)] = c.getString(1) to c.getInt(2)
        }
        return out
    }

    private fun score(corpusCount: Int, weight: Double): Long {
        val fs = if (corpusCount > 0) log10(corpusCount.toDouble()) else -1.0
        return Math.round((fs + 1.0) * 100000) * 65536L + Math.round(weight * 65535)
    }

    private fun boost(word: String): Long {
        val n = taps[word] ?: return 0L
        return Math.min(n, 8) * 50000L * 65536L
    }

    private fun copyAsset(target: File) {
        context.assets.open(DB_NAME).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    private fun open(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

    private fun schemaVersion(database: SQLiteDatabase): String =
        database.rawQuery("SELECT v FROM meta WHERE k='schema_version'", null).use { c ->
            if (c.moveToNext()) c.getString(0) else ""
        }

    private fun loadTaps() {
        taps.clear()
        val raw = prefs.getString(KEY_TAPS, null) ?: return
        try {
            val json = JSONObject(raw)
            for (key in json.keys()) taps[key] = json.getInt(key)
        } catch (e: Exception) {
            Log.e(TAG, "tap memory reset")
        }
    }

    companion object {
        private const val TAG = "VoiceImeLexicon"
        private const val DB_NAME = "lexicon.db"
        private const val SCHEMA_VERSION = "1"
        private const val PREFS = "voiceime_lexicon"
        private const val KEY_TAPS = "taps"
        private const val TOP_N = 50
        private const val MAX_TAPS = 1000
    }
}
