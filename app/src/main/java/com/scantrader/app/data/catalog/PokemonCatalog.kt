package com.scantrader.app.data.catalog

import java.text.Normalizer

/**
 * Which TCGdex languages to query, and how to keep a hit.
 * English and Italian share a catalog id. Japanese, Simplified Chinese and Traditional Chinese do not.
 */
object PokemonCatalog {
    const val EN = "en"
    const val IT = "it"
    const val JA = "ja"
    const val ZH_CN = "zh-cn"
    const val ZH_TW = "zh-tw"

    private val variantTokens = setOf("ex", "gx", "v", "vmax", "vstar", "lv", "lvx", "break")

    fun languages(name: String?, setCode: String?): List<String> {
        val fromScript = scriptLanguages(name)
        val fromSet = setLanguages(setCode)
        val merged = (fromScript + fromSet).distinct()
        return merged.ifEmpty { listOf(EN, IT) }
    }

    fun scriptLanguages(name: String?): List<String> {
        val text = name?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val kana = text.count { it in '\u3040'..'\u30ff' || it == 'ー' }
        val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val latin = text.count { it.isLetter() && it.code < 0x250 }
        // Latin-dominant (Italian/EN OCR with CJK noise) → do not query JA/ZH.
        if (latin >= 3 && latin >= (kana + han)) return listOf(EN, IT)
        return when {
            kana > 0 -> listOf(JA, ZH_TW, ZH_CN)
            han > 0 && latin == 0 -> listOf(ZH_TW, ZH_CN, JA)
            han > 0 -> listOf(EN, IT, JA, ZH_TW, ZH_CN)
            else -> listOf(EN, IT)
        }
    }

    fun setLanguages(setCode: String?): List<String> {
        val raw = setCode?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (PokemonSets.isInternationalPrinted(raw)) return listOf(EN, IT)
        if (raw.startsWith("csv", ignoreCase = true) || raw.startsWith("cs", ignoreCase = true)) {
            return listOf(ZH_CN, ZH_TW)
        }
        if (raw.any { it.isDigit() }) return listOf(JA, ZH_TW, ZH_CN)
        return emptyList()
    }

    fun nameQueries(name: String?): List<String> {
        val full = name?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (full.any { it.code > 0x2E80 } && full.none { it.isLetter() && it.code < 0x250 }) {
            return listOf(full)
        }
        val out = linkedSetOf(full)
        val parts = full.split(Regex("""\s+""")).filter { it.length >= 3 }
        parts.firstOrNull()?.let { out += it }
        if (parts.size >= 2) {
            out += parts.take(2).joinToString(" ")
            out += parts.takeLast(2).joinToString(" ")
        }
        // "Kilowattrel di Kissara" → also "Kilowattrel"
        val withoutOwner = full.replace(Regex("""(?i)\b(del|della|dei|delle|di|the|of|and)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (withoutOwner.length >= 3) out += withoutOwner
        withoutOwner.split(Regex("""\s+""")).firstOrNull { it.length >= 4 }?.let { out += it }
        return out.filter { it.length >= 2 }.distinct().take(5)
    }

    /** TCGdex card ids. International numbers are zero-padded (`sv03-001`); both forms are tried. */
    fun cardIds(setId: String, number: String): List<String> {
        val raw = number.trim()
        val stripped = raw.trimStart('0').ifBlank { raw }
        val padded = stripped.padStart(3, '0')
        return listOf("$setId-$raw", "$setId-$stripped", "$setId-$padded")
            .distinct()
    }

    fun numberMatches(searchNumber: String?, cardNumber: String?): Boolean {
        if (searchNumber.isNullOrBlank()) return true
        val cardRaw = cardNumber?.trim().orEmpty()
        if (cardRaw.isEmpty()) return false
        val rawSearchFull = searchNumber.split("/").first().trim()
        val rawCardFull = cardRaw.split("/").first().trim()
        val rawSearch = rawSearchFull.trimStart('0').ifBlank { "0" }
        val rawCard = rawCardFull.trimStart('0').ifBlank { "0" }
        val searchDigits = digits(rawSearchFull)
        val cardDigits = digits(rawCardFull)
        if (searchDigits.isNotEmpty() && cardDigits.isNotEmpty() && searchDigits == cardDigits) return true
        if (rawSearch.equals(rawCard, ignoreCase = true)) return true
        val nnum = norm(searchNumber)
        val cn = norm(cardRaw)
        if (cn == nnum) return true
        if (nnum.all { it.isDigit() }) return false
        return cn.contains(nnum) || nnum.contains(cn)
    }

    fun nameMatches(cardName: String?, queryName: String?): Boolean {
        val card = normCardName(cardName.orEmpty())
        val query = normCardName(queryName.orEmpty())
        if (query.isBlank()) return true
        if (card.isBlank()) return false
        if (card == query || card.contains(query) || query.contains(card)) return true
        if (query.any { it.code > 0x2E80 } || card.any { it.code > 0x2E80 }) return false

        val cardTokens = card.split(" ").filter { it.isNotBlank() }
        val queryTokens = query.split(" ").filter { it.isNotBlank() }
        if (cardTokens.isEmpty() || queryTokens.isEmpty()) return false
        val matched = queryTokens.count { token ->
            token.length > 1 && cardTokens.any { candidate ->
                candidate == token || candidate.startsWith(token) || token.startsWith(candidate)
            }
        }
        if (matched >= minOf(queryTokens.size, 2)) return true
        val cardCore = cardTokens.filter { it != "and" && it !in variantTokens }
        val queryCore = queryTokens.filter { it != "and" && it !in variantTokens }
        if (queryCore.isNotEmpty() && queryCore.all { token ->
                cardCore.any { it == token || it.startsWith(token) || token.startsWith(it) }
            }
        ) {
            val queryVariant = queryTokens.any { it in variantTokens }
            val cardVariant = cardTokens.any { it in variantTokens }
            if (!queryVariant || cardVariant) return true
        }
        return false
    }

    fun score(
        cardName: String,
        cardNumber: String,
        setId: String,
        printedTotal: Int?,
        queryName: String?,
        queryNumber: String?,
        querySetIds: List<String>,
        setCardCount: Int?,
    ): Int {
        var score = 0
        if (!queryNumber.isNullOrBlank() && numberMatches(queryNumber, cardNumber)) score += 50
        if (querySetIds.any { it.equals(setId, ignoreCase = true) }) score += 30
        if (setCardCount != null && printedTotal == setCardCount) score += 35
        if (!queryName.isNullOrBlank()) {
            if (nameMatches(cardName, queryName)) {
                score += if (normCardName(cardName) == normCardName(queryName)) 45 else 25
            } else {
                // Strong penalty: number-only hits from other languages/sets (Swoobat #72, etc.).
                score -= 45
            }
        }
        return score
    }

    private fun digits(value: String): String =
        Regex("""\d+""").find(value)?.value?.trimStart('0')?.ifBlank { "0" }.orEmpty()

    private fun normCardName(value: String): String =
        norm(value)
            .replace("♀", "")
            .replace("♂", "")
            .replace("&", " ")
            .replace(Regex("""[.\-_]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun norm(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return decomposed.lowercase().trim()
    }
}
