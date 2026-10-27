package com.scantrader.app.data.catalog

/**
 * Scryfall set codes (`fra`, `eld`, `mh3`). Loaded once from
 * `catalog/mtg-sets.txt`. An OCR token that is not in this list is not a set.
 */
object MtgSets {
    private val codes: Set<String>? by lazy { load() }

    fun isKnown(code: String?): Boolean {
        val key = code?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        val known = codes ?: return true
        return key in known
    }

    private fun load(): Set<String>? {
        val stream = MtgSets::class.java.classLoader?.getResourceAsStream("catalog/mtg-sets.txt")
            ?: return null
        return stream.bufferedReader().use { reader ->
            reader.lineSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .toSet()
        }.takeIf { it.isNotEmpty() }
    }
}
