package com.voiceime.lexicon

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.voiceime.Candidate
import com.voiceime.CandidateProvider
import com.voiceime.CandidateResult
import com.voiceime.LearnedWord
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
    private var learnedDb: SQLiteDatabase? = null
    private val sylByDigits = HashMap<String, List<String>>()
    private val taps = HashMap<String, Int>()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** Cloud lexicon DB (built by CloudLexicon); null when offline / not synced. */
    @Volatile
    private var cloudDb: SQLiteDatabase? = null
    /** First (most frequent) pinyin reading per character, built in prepare(). */
    private val charPinyinMap = HashMap<Char, String>()
    /** All 424 legal syllables, built in prepare() from the syl table. */
    private var syllableSet: Set<String> = emptySet()
    /** Built-in (word|pinyin) pairs, for cloud dedupe; built lazily. */
    private var builtInPairsCache: Set<String>? = null

    /** Copy the asset DB once, open it, load the syllable table + tap memory. */
    fun prepare() {
        openLearnedDb()
        loadTaps()
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
            buildDerivedIndexes(database)
        } catch (e: Exception) {
            // Typing must keep working even if the lexicon cannot be opened.
            Log.e(TAG, "lexicon prepare failed: ${e.javaClass.simpleName}")
            db = null
        }
    }

    override fun lookup(digits: String): CandidateResult = lookup(digits, null)

    override fun lookupFor(digits: String, segmentation: String): CandidateResult =
        lookup(digits, segmentation)

    /**
     * [chosenSeg] null = default view: candidates ranked globally, label shows
     * the segmentation behind the top word. Non-null = only words readable
     * with that segmentation, no partial-input suggestions.
     */
    private fun lookup(digits: String, chosenSeg: String?): CandidateResult {
        val database = db ?: return CandidateResult(digits, emptyList())
        if (digits.isEmpty()) return CandidateResult("", emptyList())
        return try {
            val segs = segment(digits)
            val keys = segs.map { it.joinToString("") }.distinct()

            // (wordId, weight, pinyinKey) for every hit across ALL segmentations
            val entries = ArrayList<Triple<Int, Double, String>>()
            for (key in keys) entries.addAll(readKey(database, key))

            val words = readWords(database, entries.map { it.first }.distinct())

            // Rank segmentations by the score of the best word each produces,
            // so the label defaults to the reading behind the first choice.
            val keyBest = HashMap<String, Long>()
            for ((wid, weight, key) in entries) {
                val wf = words[wid] ?: continue
                val s = score(wf.second, weight) + boost(wf.first)
                val prev = keyBest[key]
                if (prev == null || s > prev) keyBest[key] = s
            }
            // Rank segmentations by fewest segments first (longest syllables),
            // then by the best word each produces. "ni hao" (2 segments) must
            // outrank "ni ha o" (3) even when both join to the same key.
            val rankedSegs = segs.map { it.joinToString(" ") }.distinct()
                .sortedWith(
                    compareBy<String> { it.split(" ").size }
                        .thenByDescending { keyBest[it.replace(" ", "")] ?: Long.MIN_VALUE }
                )
                .take(SEG_N)
            val pinyin = chosenSeg ?: rankedSegs.firstOrNull() ?: digits

            val chosenKey = chosenSeg?.replace(" ", "")
            val pool = if (chosenKey == null) entries else entries.filter { it.third == chosenKey }
            val best = HashMap<String, Long>()
            val bestKey = HashMap<String, String>()
            for ((wid, weight, key) in pool) {
                val wf = words[wid] ?: continue
                val s = score(wf.second, weight) + boost(wf.first)
                val prev = best[wf.first]
                if (prev == null || s > prev) {
                    best[wf.first] = s
                    bestKey[wf.first] = key
                }
            }
            // Cloud lexicon (when synced): same pinyin, merged in and
            // re-ranked by the same score. Words already in the built-in
            // lexicon were never stored in the cloud DB, so a cloud word
            // only wins if its score is higher. Cloud pinyin is stored
            // space-separated ("ni hao"), unlike the built-in key ("nihao").
            cloudDb?.let { cloud ->
                try {
                    val cloudKeys = if (chosenSeg != null) listOf(chosenSeg)
                    else segs.map { it.joinToString(" ") }.distinct()
                    for (pinyin in cloudKeys) {
                        cloud.rawQuery(
                            "SELECT word, freq FROM cloud_word WHERE pinyin=?",
                            arrayOf(pinyin)
                        ).use { c ->
                            while (c.moveToNext()) {
                                val word = c.getString(0)
                                val s = score(c.getInt(1), CLOUD_WEIGHT) + boost(word)
                                val prev = best[word]
                                if (prev == null || s > prev) {
                                    best[word] = s
                                    bestKey[word] = pinyin.replace(" ", "")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "cloud lookup failed: ${e.javaClass.simpleName}")
                }
            }

            val ranked = best.entries.sortedByDescending { it.value }.take(TOP_N)
            val cands = ArrayList<Candidate>(ranked.size + 30)
            for (e in ranked) cands.add(Candidate(e.key, bestKey[e.key] ?: ""))

            // Partial-input suggestions from the prebuilt digit-prefix index
            // (default view only; they do not belong to a chosen segmentation).
            if (chosenKey == null) {
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
            }

            if (cands.isEmpty() && segs.isNotEmpty()) {
                // Rare phrase dropped by the build-time trim: offer the chars of
                // the first syllable so the user can build the word char by char.
                val firstSyl = chosenSeg?.split(" ")?.firstOrNull() ?: segs[0][0]
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
            CandidateResult(pinyin, cands, rankedSegs)
        } catch (e: Exception) {
            Log.e(TAG, "lookup failed: ${e.javaClass.simpleName}")
            CandidateResult(digits, emptyList())
        }
    }

    override fun rememberTap(word: String) {
        val n = min((taps[word] ?: 0) + 1, 100)
        taps[word] = n
        if (taps.size > MAX_TAPS) {
            val coldest = taps.entries.sortedBy { it.value }.take(taps.size - MAX_TAPS)
            coldest.forEach {
                taps.remove(it.key)
                learnedDb?.delete("learned", "word=?", arrayOf(it.key))
            }
        }
        learnedDb?.let { database ->
            val values = ContentValues().apply {
                put("word", word)
                put("count", n)
                put("last_used", System.currentTimeMillis())
            }
            val updated = database.update("learned", values, "word=?", arrayOf(word))
            if (updated == 0) database.insert("learned", null, values)
        }
    }

    /**
     * Pinyin string for the online query: the first full segmentation when one
     * exists; otherwise the longest segmentable prefix plus a best-effort letter
     * per leftover digit (partial pinyin is accepted by the endpoint).
     */
    override fun pinyinFor(digits: String): String {
        val segs = segment(digits)
        if (segs.isNotEmpty()) return segs[0].joinToString("")
        for (end in digits.length - 1 downTo 1) {
            val s = segment(digits.substring(0, end))
            if (s.isNotEmpty()) {
                val rest = digits.substring(end).map { FIRST_LETTER[it] ?: "" }.joinToString("")
                return s[0].joinToString("") + rest
            }
        }
        return digits.map { FIRST_LETTER[it] ?: "" }.joinToString("")
    }

    /**
     * Build the derived indexes, on the calling (background) thread:
     * the legal-syllable set, and the first pinyin reading per character
     * (the reading of that character's highest-frequency single-character
     * word). Both feed correction context and cloud proper-noun pinyin.
     * Runs once per prepare(); typing never waits for it.
     */
    private fun buildDerivedIndexes(database: SQLiteDatabase) {
        syllableSet = sylByDigits.values.flatten().toSet()
        val words = HashMap<Int, Pair<String, Int>>()
        database.rawQuery("SELECT id, w, f FROM word", null).use { c ->
            while (c.moveToNext()) words[c.getInt(0)] = c.getString(1) to c.getInt(2)
        }
        val best = HashMap<Char, Pair<String, Int>>()
        database.rawQuery("SELECT p, ws FROM pykey", null).use { c ->
            while (c.moveToNext()) {
                val pinyin = c.getString(0)
                val blob = c.getBlob(1)
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
                    pos += 2 // u16 weight, not needed here
                    val wf = words[wid] ?: continue
                    if (wf.first.length != 1) continue
                    val ch = wf.first[0]
                    val prev = best[ch]
                    if (prev == null || wf.second > prev.second) best[ch] = pinyin to wf.second
                }
            }
        }
        charPinyinMap.clear()
        for ((ch, py) in best) charPinyinMap[ch] = py.first
        builtInPairsCache = null
    }

    /** All legal pinyin syllables (424), from the syl table. */
    fun syllables(): Set<String> = syllableSet

    /** First (most frequent) pinyin reading of a character, or null. */
    fun charPinyin(ch: Char): String? = charPinyinMap[ch]

    /** Learned words with tap counts, for the English engine's learned-first. */
    fun learnedMap(): Map<String, Int> = HashMap(taps)

    /** Built-in (word|pinyin) pairs, for cloud dedupe; built once, lazily. */
    fun wordPinyinPairs(): Set<String> {
        builtInPairsCache?.let { return it }
        val database = db ?: return emptySet()
        val out = HashSet<String>()
        try {
            val words = HashMap<Int, String>()
            database.rawQuery("SELECT id, w FROM word", null).use { c ->
                while (c.moveToNext()) words[c.getInt(0)] = c.getString(1)
            }
            database.rawQuery("SELECT p, ws FROM pykey", null).use { c ->
                while (c.moveToNext()) {
                    val pinyin = c.getString(0)
                    val blob = c.getBlob(1)
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
                        pos += 2
                        val word = words[wid] ?: continue
                        out.add("$word|$pinyin")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "wordPinyinPairs failed: ${e.javaClass.simpleName}")
        }
        builtInPairsCache = out
        return out
    }

    /** Attach (or detach, with null) the cloud lexicon DB. */
    fun attachCloudDb(database: SQLiteDatabase?) {
        cloudDb = database
    }

    // ---- segmentation (exact port of t9_verify.py segment()) ----------------

    private fun segment(digits: String, cap: Int = 200): List<List<String>> {
        val memo = HashMap<Int, List<List<String>>>()
        fun rec(i: Int): List<List<String>> {
            if (i == digits.length) return listOf(emptyList())
            memo[i]?.let { return it }
            val out = ArrayList<List<String>>()
            // Longest first: a chunk that can form a longer legal syllable
            // must be tried before its prefixes, so "hao" wins over "ha"+"o".
            // The ranking below (fewest segments first) then picks the
            // segmentation with the longest syllables overall.
            for (len in 6 downTo 1) {
                // Lengths are tried longest-first: an over-long length is
                // skipped (continue), it must NOT abort the loop (break) --
                // break here would kill every input shorter than 6 digits.
                if (i + len > digits.length) continue
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

    /**
     * Local single-edit correction (v1.3.0). ONLY for input that cannot be
     * spelled at all: no legal segmentation (and therefore no candidates).
     * Legal syllables are NEVER corrected here — hou vs hao must wait for
     * the AI layer, which sees the full context.
     *
     * Tries one substitution / deletion / insertion of a single digit and
     * returns the corrected digit string with its candidates when exactly
     * one variant produces a strong common word with a clear margin over
     * the runner-up (top-word frequency at least CORRECT_MARGIN times the
     * runner-up's). Context helps: when the preceding committed character
     * plus the corrected word form a known word, that variant's frequency
     * counts double.
     *
     * Runs on a background thread; callers only call it when lookup()
     * came back empty. Returns null when not confident.
     */
    fun suggestCorrection(digits: String, beforeText: String): Pair<String, List<Candidate>>? {
        val database = db ?: return null
        if (digits.length < 2 || digits.length > 20) return null
        if (segment(digits).isNotEmpty()) return null
        val variants = LinkedHashSet<String>()
        for (i in digits.indices) {
            for (d in "23456789") {
                if (d == digits[i]) continue
                variants.add(digits.substring(0, i) + d + digits.substring(i + 1))
            }
            variants.add(digits.removeRange(i, i + 1))
        }
        for (i in 0..digits.length) {
            for (d in "23456789") {
                variants.add(digits.substring(0, i) + d + digits.substring(i))
            }
        }
        var bestVariant: String? = null
        var bestFreq = 0
        var secondFreq = 0
        for (v in variants) {
            val segs = segment(v)
            if (segs.isEmpty()) continue
            val top = topWord(v) ?: continue
            var freq = top.second
            if (contextMatch(top.first, segs[0][0], beforeText)) freq *= CONTEXT_FREQ_MULT
            if (freq > bestFreq) {
                secondFreq = bestFreq
                bestFreq = freq
                bestVariant = v
            } else if (freq > secondFreq) {
                secondFreq = freq
            }
        }
        val corrected = bestVariant ?: return null
        if (bestFreq < CORRECT_MIN_FREQ) return null
        if (secondFreq > 0 && bestFreq < CORRECT_MARGIN * secondFreq) return null
        val result = lookup(corrected, null)
        if (result.candidates.isEmpty()) return null
        return corrected to result.candidates
    }

    /** Highest-scoring word for a digit string, with its corpus count. */
    private fun topWord(digits: String): Pair<String, Int>? {
        val database = db ?: return null
        val segs = segment(digits)
        if (segs.isEmpty()) return null
        val keys = segs.map { it.joinToString("") }.distinct()
        val entries = ArrayList<Triple<Int, Double, String>>()
        for (key in keys) entries.addAll(readKey(database, key))
        if (entries.isEmpty()) return null
        val words = readWords(database, entries.map { it.first }.distinct())
        var best: Pair<String, Int>? = null
        var bestScore = Long.MIN_VALUE
        for ((wid, weight, _) in entries) {
            val wf = words[wid] ?: continue
            val s = score(wf.second, weight) + boost(wf.first)
            if (s > bestScore) {
                bestScore = s
                best = wf
            }
        }
        return best
    }

    /** Does (last committed char + corrected word) form a known word? */
    private fun contextMatch(word: String, firstSyl: String, beforeText: String): Boolean {
        if (beforeText.isEmpty() || word.isEmpty()) return false
        val lastChar = beforeText.last()
        val lastPy = charPinyin(lastChar) ?: return false
        val database = db ?: return false
        val entries = readKey(database, lastPy + firstSyl)
        if (entries.isEmpty()) return false
        val words = readWords(database, entries.map { it.first }.distinct())
        return words.values.any { it.first == "$lastChar$word" }
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

    /** Learned words live in their own SQLite database (local only, never
     *  uploaded). The bundled lexicon.db is read-only and re-copied from
     *  assets on a schema bump, so learning must not live in it. */
    private fun openLearnedDb() {
        try {
            val file = File(context.filesDir, LEARNED_DB_NAME)
            learnedDb = SQLiteDatabase.openOrCreateDatabase(file.path, null)
            learnedDb?.execSQL(
                "CREATE TABLE IF NOT EXISTS learned(" +
                "word TEXT PRIMARY KEY, count INTEGER NOT NULL, " +
                "last_used INTEGER NOT NULL)"
            )
            migrateLegacyTaps()
        } catch (e: Exception) {
            Log.e(TAG, "learned db unavailable: ${e.javaClass.simpleName}")
            learnedDb = null
        }
    }

    /** One-time move of the old SharedPreferences tap memory into the DB. */
    private fun migrateLegacyTaps() {
        val raw = prefs.getString(KEY_TAPS, null) ?: return
        try {
            val json = JSONObject(raw)
            val now = System.currentTimeMillis()
            for (key in json.keys()) {
                val values = ContentValues().apply {
                    put("word", key)
                    put("count", json.getInt(key))
                    put("last_used", now)
                }
                learnedDb?.insertWithOnConflict(
                    "learned", null, values, SQLiteDatabase.CONFLICT_IGNORE
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "tap migration failed: ${e.javaClass.simpleName}")
        } finally {
            prefs.edit().remove(KEY_TAPS).apply()
        }
    }

    private fun loadTaps() {
        taps.clear()
        val database = learnedDb ?: return
        try {
            database.rawQuery("SELECT word, count FROM learned", null).use { c ->
                while (c.moveToNext()) taps[c.getString(0)] = c.getInt(1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "tap load failed: ${e.javaClass.simpleName}")
        }
    }

    /** All learned words, most-used first, for the Settings export. */
    override fun learnedWords(): List<LearnedWord> {
        val database = learnedDb
        if (database == null) {
            return taps.entries.sortedByDescending { it.value }
                .map { LearnedWord(it.key, it.value, 0L) }
        }
        val out = ArrayList<LearnedWord>()
        try {
            database.rawQuery(
                "SELECT word, count, last_used FROM learned ORDER BY count DESC, last_used DESC",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(LearnedWord(c.getString(0), c.getInt(1), c.getLong(2)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "learned list failed: ${e.javaClass.simpleName}")
        }
        return out
    }

    companion object {
        private const val TAG = "VoiceImeLexicon"
        private const val DB_NAME = "lexicon.db"
        private const val LEARNED_DB_NAME = "learned.db"
        private const val SCHEMA_VERSION = "2"
        private const val PREFS = "voiceime_lexicon"
        private const val KEY_TAPS = "taps"
        private const val TOP_N = 50
        private const val SEG_N = 20
        private const val MAX_TAPS = 1000
        /** Dict weight used for cloud words (they carry no dict weight). */
        private const val CLOUD_WEIGHT = 0.5
        /** A correction must be a common word (corpus count at least this). */
        private const val CORRECT_MIN_FREQ = 1000
        /** A correction's top word must be this many times more frequent
         *  than the runner-up variant's top word (a clear margin). */
        private const val CORRECT_MARGIN = 3
        /** Context match (preceding char + corrected word form a known word)
         *  doubles the variant's frequency when comparing. */
        private const val CONTEXT_FREQ_MULT = 2
        private val FIRST_LETTER = mapOf(
            '2' to "a", '3' to "d", '4' to "g", '5' to "j",
            '6' to "m", '7' to "p", '8' to "t", '9' to "w"
        )
    }
}
