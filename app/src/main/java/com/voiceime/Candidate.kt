package com.voiceime

/** One candidate word shown in the candidate bar. */
data class Candidate(val word: String, val pinyin: String = "")

/** Local lookup result for a T9 digit string. */
data class CandidateResult(val pinyin: String, val candidates: List<Candidate>)

/**
 * Local-first candidate source. Implementations must be fast (indexed DB,
 * no network, no full-table scans) because this runs on every keypress.
 */
interface CandidateProvider {
    fun lookup(digits: String): CandidateResult
    fun rememberTap(word: String) {}

    /** Best pinyin reading of a digit string, for the online suggestion query. */
    fun pinyinFor(digits: String): String = digits
}
