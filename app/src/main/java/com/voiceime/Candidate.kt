package com.voiceime

/** One candidate word shown in the candidate bar. [isAi] marks an AI
 *  suggestion (from the /typo check): it appears with an "AI" tag and
 *  never commits by itself — the user taps it to apply. [aiOriginal] is
 *  the suspect word it would replace. */
data class Candidate(
    val word: String,
    val pinyin: String = "",
    val isAi: Boolean = false,
    val aiOriginal: String? = null
)

/** One word the user has picked, with how often and when last used. */
data class LearnedWord(val word: String, val count: Int, val lastUsed: Long)

/**
 * Local lookup result for a T9 digit string. [segmentations] is the ranked
 * list of space-joined pinyin readings, best-word score first; [pinyin] is
 * the currently displayed one (by default the one behind the top candidate).
 */
data class CandidateResult(
    val pinyin: String,
    val candidates: List<Candidate>,
    val segmentations: List<String> = emptyList()
)

/**
 * Local-first candidate source. Implementations must be fast (indexed DB,
 * no network, no full-table scans) because this runs on every keypress.
 */
interface CandidateProvider {
    fun lookup(digits: String): CandidateResult
    fun rememberTap(word: String) {}

    /** All learned words, most-used first, for the Settings export. */
    fun learnedWords(): List<LearnedWord> = emptyList()

    /** Candidates restricted to one chosen segmentation (space-joined pinyin). */
    fun lookupFor(digits: String, segmentation: String): CandidateResult = lookup(digits)

    /** Best pinyin reading of a digit string, for the online suggestion query. */
    fun pinyinFor(digits: String): String = digits
}
