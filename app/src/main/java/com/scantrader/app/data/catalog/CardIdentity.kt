package com.scantrader.app.data.catalog

import java.text.Normalizer

/**
 * Compares OCR reads of the same physical card.
 * Names are normalized (accents, case, 1–2 letter crumbs such as "di" / "i")
 * and matched with Levenshtein similarity instead of exact equality.
 */
object CardIdentity {
    const val NAME_SIMILARITY = 0.8

    /** Stage / layout crumbs OCR promotes to a title: FASE, FASEJ, FASEl, CASEl, A!. */
    fun isStageNoise(raw: String): Boolean {
        val letters = raw.lowercase().replace(Regex("""[^a-z]"""), "")
        if (letters.isEmpty()) return false
        if (letters in setOf("fa", "fase", "fasei", "faseii", "stage", "stadio", "basic")) return true
        return letters.length in 4..7 && letters.matches(Regex("""[efcr]ase[a-z]{0,3}"""))
    }

    /**
     * "Kilowattrel a/i/ai/d Kissara" and "KilowattreldiKissara" are the Italian
     * owner title "Kilowattrel di Kissara" (Kissara's Kilowattrel).
     */
    fun repairOwnerName(raw: String): String {
        val glued = raw.replace(
            Regex("""(?i)(?<=[A-Za-zÀ-ÖØ-öø-ÿ])di(?=[A-ZÀ-Ö])"""),
            " di ",
        )
        val parts = glued.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (parts.size == 3 && parts[1].lowercase() in setOf("a", "i", "ai", "d", "o")) {
            return "${parts[0]} di ${parts[2]}"
        }
        return parts.joinToString(" ")
    }

    /** English catalog form of an Italian "Species di Trainer" title. */
    fun englishOwnerName(raw: String): String? {
        val match = Regex("""(?i)^(.+?)\s+di\s+(.+)$""").find(raw.trim()) ?: return null
        val species = match.groupValues[1].trim()
        val trainer = match.groupValues[2].trim()
        if (species.length < 3 || trainer.length < 3) return null
        if (species.any { it.isWhitespace() }) return null
        return "$trainer's $species"
    }

    fun namesSimilar(left: String?, right: String?, threshold: Double = NAME_SIMILARITY): Boolean {
        val a = normalizeName(left)
        val b = normalizeName(right)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        if (shorter.length >= 4 && longer.contains(shorter)) return true
        return similarity(a, b) >= threshold
    }

    /**
     * True when every significant query token fuzzy-matches a card token.
     * Covers "Kilowattrel di Kissara" vs "Kissara's Kilowattrel" and "Kisara" vs "Kissara".
     */
    fun ownerTokensMatch(cardName: String, queryName: String): Boolean {
        val cardTokens = significantTokens(cardName)
        val queryTokens = significantTokens(queryName)
        if (cardTokens.isEmpty() || queryTokens.size < 2) return false
        return queryTokens.all { query -> cardTokens.any { tokenSimilar(query, it) } }
    }

    fun normalizeName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return decomposed.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length >= 3 }
            .joinToString(" ")
            .trim()
    }

    fun normCollector(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val stripped = trimmed.trimStart('0').ifBlank { "0" }
        return stripped
    }

    fun similarity(left: String, right: String): Double {
        if (left == right) return 1.0
        val denom = maxOf(left.length, right.length)
        if (denom == 0) return 1.0
        return 1.0 - levenshtein(left, right).toDouble() / denom
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val swap = prev
            prev = curr
            curr = swap
        }
        return prev[b.length]
    }

    private fun significantTokens(raw: String): List<String> =
        Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .lowercase()
            .replace(Regex("""['’]"""), " ")
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length >= 3 && it !in DROP_TOKENS }

    private fun tokenSimilar(left: String, right: String): Boolean {
        if (left == right) return true
        val shorter = if (left.length <= right.length) left else right
        val longer = if (left.length <= right.length) right else left
        if (shorter.length >= 4 && (longer.startsWith(shorter) || longer.endsWith(shorter))) return true
        return similarity(left, right) >= NAME_SIMILARITY
    }

    private val DROP_TOKENS = setOf("del", "della", "dei", "delle", "the", "and")
}
