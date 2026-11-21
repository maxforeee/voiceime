package com.voiceime.lexicon

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * English 9-key engine (v1.3.0, our own algorithm).
 *
 * The user types digit keys; each key is 3-4 letters (2=abc ... 9=wxyz).
 * The engine never guesses letters: it enumerates the letter combinations
 * the pressed keys can spell and ranks the real words that match.
 *
 * Ranking (see docs/RANKING_ALGORITHM.md — the same rules in prose):
 *   1. Learned words first (the user's own picks, from learned.db, by tap
 *      count). A word the user picked before beats any public frequency.
 *   2. Common words next (wordfreq top 10,000 + proper nouns, Zipf order).
 *      They live in a digit-prefix trie: each node holds the 30 most
 *      frequent words whose digit signature starts with that node's prefix.
 *   3. Uncommon words last (the 370k-word alpha list), separated from the
 *      common ones by a divider row. Ranked by Zipf when the cloud loader
 *      has supplied it, otherwise in signature order.
 * A locked letter (the user tapped one in the dropdown) is a filter, not a
 * guess: only words with that exact letter at that position survive.
 *
 * Loading and trie building run on a background thread; lookups before the
 * engine is ready return an empty result (never block typing).
 */
class EnglishLexicon(private val context: Context) {

    data class EnglishResult(
        /** Letters the last pressed key can be; empty when there is no key yet. */
        val letters: List<Char>,
        /** The locked letter at the last position, or null. */
        val selectedLetter: Char?,
        /** Common words: learned first, then tier-1 by frequency. Max 30. */
        val common: List<String>,
        /** Uncommon words (tier-2). Max 10. */
        val rare: List<String>,
    )

    /** A local correction: the corrected digit string plus its locks. */
    data class Corrected(val digits: String, val locked: List<Char?>)

    @Volatile
    var ready: Boolean = false
        private set

    /** Tier-1 words in frequency order (line order of english_words.txt). */
    private var words: List<String> = emptyList()

    /** Digit-prefix trie over tier-1; swapped atomically after a rebuild. */
    @Volatile
    private var trieRoot: TrieNode? = null

    /** Tier-2 file content (signature-sorted "sig\tword" lines) + line offsets. */
    private var rareText: String? = null
    private var rareOffsets: IntArray = IntArray(0)

    /** Cloud Zipf ranks for tier-2 words (word -> zipf), when loaded. */
    @Volatile
    private var rareZipf: Map<String, Float> = emptyMap()

    /** Load both assets on the caller's thread (call from a background thread). */
    fun prepare(onReady: (() -> Unit)? = null) {
        val tier1 = ArrayList<String>()
        try {
            context.assets.open("english_words.txt").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).forEachLine { line ->
                    val w = line.trim().lowercase()
                    if (w.isNotEmpty() && w.all { it in 'a'..'z' }) tier1.add(w)
                }
            }
        } catch (e: Exception) {
            // No wordlist: the engine stays not-ready and lookups return empty.
        }
        words = tier1
        trieRoot = buildTrie(tier1)
        // Tier-2: one big string + line offsets, so a prefix query is a binary
        // search plus a bounded forward scan (no per-word objects in memory).
        try {
            val text = context.assets.open("english_words_rare.txt").use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
            val offsets = ArrayList<Int>()
            var i = 0
            offsets.add(0)
            while (i < text.length) {
                if (text[i] == '\n' && i + 1 < text.length) offsets.add(i + 1)
                i++
            }
            rareText = text
            rareOffsets = offsets.toIntArray()
        } catch (e: Exception) {
            rareText = null
            rareOffsets = IntArray(0)
        }
        ready = words.isNotEmpty()
        onReady?.invoke()
    }

    /** Append cloud English words (Zipf order) to tier-1 and rebuild the trie. */
    fun addCloudWords(cloudWords: List<String>) {
        if (cloudWords.isEmpty()) return
        val merged = ArrayList(words)
        val seen = merged.toHashSet()
        for (w in cloudWords) {
            if (w !in seen) {
                seen.add(w)
                merged.add(w)
            }
        }
        words = merged
        trieRoot = buildTrie(merged)
    }

    fun setRareZipf(zipf: Map<String, Float>) {
        rareZipf = zipf
    }

    /** Frequency rank of a tier-1 word (0 = most frequent), or -1. */
    fun wordRank(word: String): Int = words.indexOf(word)

    /** Tier-1 words (built-in + cloud), for cloud dedupe. */
    fun tier1Words(): Set<String> = words.toSet()

    fun signatureOf(word: String): String = buildString(word.length) {
        for (c in word) append(LETTER_TO_DIGIT[c] ?: '?')
    }

    /**
     * Look up the current pattern.
     * @param digits pressed keys, e.g. "4663".
     * @param locked one locked letter per position (null = not locked yet).
     * @param learned the user's picked words (word -> tap count).
     */
    fun lookup(digits: String, locked: List<Char?>, learned: Map<String, Int>): EnglishResult {
        val lastDigit = digits.lastOrNull()
        val letters = if (lastDigit == null) emptyList()
            else LETTER_GROUPS[lastDigit]?.toList() ?: emptyList()
        val selected = if (locked.isNotEmpty()) locked.last() else null
        if (digits.isEmpty() || !ready) {
            return EnglishResult(letters, selected, emptyList(), emptyList())
        }
        val common = ArrayList<String>()
        // Learned words first: they match the pattern and the user picked
        // them before, so they outrank any public frequency.
        val learnedMatches = ArrayList<Pair<String, Int>>()
        for ((word, count) in learned) {
            if (matchesPattern(word, digits, locked)) learnedMatches.add(word to count)
        }
        learnedMatches.sortByDescending { it.second }
        for ((word, _) in learnedMatches) {
            if (common.size >= TOP_COMMON) break
            common.add(word)
        }
        // Then tier-1 by frequency. The trie walk stops at TRIE_DEPTH; the
        // node's top-30 are filtered by the full pattern, so words longer
        // than the trie depth still match.
        val node = walkTrie(digits)
        if (node != null) {
            for (idx in node.top) {
                if (common.size >= TOP_COMMON) break
                val word = words.getOrNull(idx) ?: continue
                if (word in learned) continue
                if (!matchesPattern(word, digits, locked)) continue
                common.add(word)
            }
        }
        // Uncommon words last, from the tier-2 signature-sorted file.
        val rare = lookupRare(digits, locked)
        return EnglishResult(letters, selected, common, rare)
    }

    /**
     * Local correction, only for a pattern with no good word at all.
     * Tries one adjacent-key substitution (the key next to the pressed one
     * on the 9-key grid — a slip of the finger). Corrects only when exactly
     * one substitution produces a common word (top 3000) or a learned word:
     * if two different neighbours both give a good word, it is ambiguous
     * and nothing is changed. Returns the corrected digits + locks, or null.
     */
    fun correctByAdjacentKey(digits: String, locked: List<Char?>, learned: Map<String, Int>): Corrected? {
        if (digits.isEmpty() || !ready) return null
        var best: Corrected? = null
        var bestRank = Int.MAX_VALUE
        var producingCount = 0
        for (i in digits.indices) {
            val neighbours = ADJACENT[digits[i]] ?: continue
            for (n in neighbours) {
                if (n == digits[i]) continue
                val cand = digits.substring(0, i) + n + digits.substring(i + 1)
                // A substitution at a locked position drops that lock: the
                // old locked letter belongs to the old key, not the new one.
                val candLocked = locked.toMutableList().also { it[i] = null }
                // A learned word matching the corrected pattern is trusted
                // outright (rank 0); otherwise the best tier-1 word decides.
                var rank: Int? = null
                if (learned.any { (w, _) -> matchesPattern(w, cand, candLocked) }) {
                    rank = 0
                } else {
                    val node = walkTrie(cand)
                    if (node != null) {
                        for (idx in node.top) {
                            val word = words.getOrNull(idx) ?: continue
                            if (!matchesPattern(word, cand, candLocked)) continue
                            rank = idx
                            break
                        }
                    }
                }
                if (rank != null && rank < COMMON_RANK_LIMIT) {
                    producingCount++
                    if (rank < bestRank) {
                        bestRank = rank
                        best = Corrected(cand, candLocked)
                    }
                }
            }
        }
        return if (best != null && producingCount == 1) best else null
    }

    // ---- internals ----------------------------------------------------------

    private fun matchesPattern(word: String, digits: String, locked: List<Char?>): Boolean {
        if (word.length < digits.length) return false
        if (!signatureOf(word).startsWith(digits)) return false
        return matchesLocked(word, locked)
    }

    private fun matchesLocked(word: String, locked: List<Char?>): Boolean {
        for (i in locked.indices) {
            val want = locked[i] ?: continue
            if (i >= word.length || word[i] != want) return false
        }
        return true
    }

    /** Walk the trie as far as the digits go (at most TRIE_DEPTH levels). */
    private fun walkTrie(digits: String): TrieNode? {
        var node = trieRoot ?: return null
        var depth = 0
        for (d in digits) {
            if (depth >= TRIE_DEPTH) break
            val idx = d - '2'
            if (idx !in 0..7) return null
            node = node.children[idx] ?: return null
            depth++
        }
        return node
    }

    private fun buildTrie(wordList: List<String>): TrieNode {
        val root = TrieNode()
        for ((rank, word) in wordList.withIndex()) {
            if (rank >= TRIE_WORD_LIMIT) break
            var node = root
            val sig = signatureOf(word)
            for (j in sig.indices) {
                if (j >= TRIE_DEPTH) break
                val idx = sig[j] - '2'
                if (idx !in 0..7) break
                var child = node.children[idx]
                if (child == null) {
                    child = TrieNode()
                    node.children[idx] = child
                }
                node = child
                if (node.top.size < TOP_PER_NODE) node.top.add(rank)
            }
        }
        return root
    }

    /** Tier-2 prefix scan: binary search the signature, then a bounded scan. */
    private fun lookupRare(digits: String, locked: List<Char?>): List<String> {
        val text = rareText ?: return emptyList()
        val offsets = rareOffsets
        if (offsets.isEmpty()) return emptyList()
        // Lower bound: first line whose signature is >= digits.
        var lo = 0
        var hi = offsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val sig = signatureAt(text, offsets[mid])
            if (sig < digits) lo = mid + 1 else hi = mid
        }
        val found = ArrayList<String>()
        var scanned = 0
        var i = lo
        while (i < offsets.size && scanned < RARE_SCAN_CAP && found.size < RARE_COLLECT_CAP) {
            val start = offsets[i]
            val tab = text.indexOf('\t', start)
            if (tab < 0) break
            val sig = text.substring(start, tab)
            if (!sig.startsWith(digits)) break
            val eol = text.indexOf('\n', tab + 1).let { if (it < 0) text.length else it }
            val word = text.substring(tab + 1, eol)
            if (word.length >= digits.length && matchesLocked(word, locked)) {
                found.add(word)
            }
            scanned++
            i++
        }
        val zipf = rareZipf
        return if (zipf.isEmpty()) {
            found.take(TOP_RARE)
        } else {
            found.sortedByDescending { zipf[it] ?: 0f }.take(TOP_RARE)
        }
    }

    /** The digit signature at the start of a tier-2 line (up to the tab). */
    private fun signatureAt(text: String, start: Int): String {
        val tab = text.indexOf('\t', start)
        val end = if (tab < 0) text.indexOf('\n', start).let { if (it < 0) text.length else it } else tab
        return text.substring(start, end)
    }

    private class TrieNode {
        /** Children for digits 2..9 (index 0..7). */
        val children = arrayOfNulls<TrieNode>(8)
        /** Word ranks (tier-1 list order = frequency), most frequent first. */
        val top = ArrayList<Int>(TOP_PER_NODE)
    }

    companion object {
        private const val TOP_PER_NODE = 30
        private const val TOP_COMMON = 30
        private const val TOP_RARE = 10
        private const val RARE_SCAN_CAP = 2000
        private const val RARE_COLLECT_CAP = 200
        /** Only corrections landing in the top 3000 words are trusted. */
        private const val COMMON_RANK_LIMIT = 3000
        /** Trie indexes the first 12 letters of the most frequent 50k words. */
        private const val TRIE_DEPTH = 12
        private const val TRIE_WORD_LIMIT = 50000

        val LETTER_GROUPS: Map<Char, String> = mapOf(
            '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
            '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz"
        )

        /** Grid neighbours (8 surrounding cells) for each letter key. */
        val ADJACENT: Map<Char, String> = mapOf(
            '2' to "3456", '3' to "256", '4' to "2578", '5' to "2346789",
            '6' to "23589", '7' to "458", '8' to "45679", '9' to "568"
        )

        private val LETTER_TO_DIGIT: Map<Char, Char> =
            LETTER_GROUPS.flatMap { (digit, letters) -> letters.map { it to digit } }.toMap()
    }
}
