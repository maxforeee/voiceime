package com.voiceime.clipboard

import android.content.Context
import org.json.JSONArray

/**
 * Clipboard history: the last 10 copied text items, newest first.
 * Stored locally only (SharedPreferences) — no cloud sync, no account.
 * Dedupes consecutive repeats of the same text.
 */
class ClipboardHistory(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val items = ArrayList<String>()

    init {
        try {
            val arr = JSONArray(prefs.getString(KEY_ITEMS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotEmpty()) items.add(s)
            }
        } catch (e: Exception) {
            items.clear()
        }
    }

    fun add(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        items.remove(t)
        items.add(0, t)
        while (items.size > MAX_ITEMS) items.removeAt(items.size - 1)
        persist()
    }

    fun all(): List<String> = ArrayList(items)

    fun clear() {
        items.clear()
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "voiceime_clipboard"
        private const val KEY_ITEMS = "items"
        const val MAX_ITEMS = 10
    }
}
