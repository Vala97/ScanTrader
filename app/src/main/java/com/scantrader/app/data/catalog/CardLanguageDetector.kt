package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.CardLanguage
import java.text.Normalizer
import java.util.Locale

/**
 * Picks the confirm-screen language.
 * Magic compares the OCR title with printed names.
 * Pokémon also reads rules words and script on the card (stage, weakness, evolve-from).
 * A tie or a weak score falls back to the last choice.
 */
object CardLanguageDetector {
    fun choose(
        detectEnabled: Boolean,
        detected: CardLanguage?,
        lastChosen: CardLanguage,
    ): CardLanguage = if (detectEnabled && detected != null) detected else lastChosen

    fun detect(
        ocrName: String?,
        englishName: String?,
        printed: Map<CardLanguage, String>,
        ocrText: String? = null,
        pokemonSignals: Boolean = false,
    ): CardLanguage? {
        val named = pickName(ocrName, englishName, printed)
        val fromCard = if (pokemonSignals) {
            pickCardText(listOfNotNull(ocrText, ocrName).filter { it.isNotBlank() }.joinToString("\n"))
        } else {
            null
        }
        return when {
            named == null -> fromCard
            fromCard == null -> named.language
            named.language == fromCard -> named.language
            named.exact -> named.language
            else -> fromCard
        }
    }

    fun normalize(raw: String?): String? {
        val text = raw?.trim()?.lowercase(Locale.ROOT)
            ?.replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
        return text?.takeIf { it.length >= 3 }
    }

    private data class NamePick(val language: CardLanguage, val exact: Boolean)

    private fun pickName(
        ocrName: String?,
        englishName: String?,
        printed: Map<CardLanguage, String>,
    ): NamePick? {
        val ocr = normalize(ocrName) ?: return null
        val scored = buildList {
            if (!englishName.isNullOrBlank()) add(CardLanguage.ENGLISH to score(ocr, englishName))
            printed.forEach { (language, title) ->
                if (language != CardLanguage.ENGLISH && title.isNotBlank()) {
                    add(language to score(ocr, title))
                }
            }
        }
        val best = scored.maxByOrNull { it.second } ?: return null
        if (best.second < MIN_SCORE) return null
        val runnerUp = scored.filter { it.first != best.first }.maxOfOrNull { it.second } ?: 0f
        if (best.second < 1f && best.second - runnerUp < MIN_GAP) return null
        if (best.second >= 1f && runnerUp >= 1f) return null
        return NamePick(best.first, exact = best.second >= 1f)
    }

    private fun score(ocr: String, title: String): Float {
        val other = normalize(title) ?: return 0f
        if (ocr == other) return 1f
        val ocrTokens = tokens(ocr)
        val titleTokens = tokens(other)
        if (ocrTokens.isEmpty() || titleTokens.isEmpty()) return 0f
        val hits = ocrTokens.count { token -> titleTokens.any { it == token } }
        val union = (ocrTokens + titleTokens).distinct().size
        if (union == 0) return 0f
        return hits.toFloat() / union
    }

    private fun tokens(text: String): List<String> =
        text.split(' ').filter { it.length >= 3 }

    private data class Signal(val language: CardLanguage, val pattern: Regex, val weight: Int)

    private val pokemonSignals: List<Signal> = listOf(
        Signal(CardLanguage.ITALIAN, Regex("""\bsi\s*evol\w{0,8}\s+da\b"""), 6),
        Signal(CardLanguage.ITALIAN, Regex("""\bsi\s*eval\w{0,8}\s+da\b"""), 6),
        Signal(CardLanguage.ITALIAN, Regex("""\bsievo\w{0,8}da\b"""), 6),
        Signal(CardLanguage.ITALIAN, Regex("""\bdebolezza\b"""), 4),
        Signal(CardLanguage.ITALIAN, Regex("""\bresistenza\b"""), 3),
        Signal(CardLanguage.ITALIAN, Regex("""\bcosto\s+di\s+ritirata\b"""), 6),
        Signal(CardLanguage.ITALIAN, Regex("""\britirata\b"""), 2),
        Signal(CardLanguage.ITALIAN, Regex("""\bfase\w{0,2}\b"""), 2),
        Signal(CardLanguage.ENGLISH, Regex("""\bevolves?\s+from\b"""), 6),
        Signal(CardLanguage.ENGLISH, Regex("""\bweakness\b"""), 4),
        Signal(CardLanguage.ENGLISH, Regex("""\bresistance\b"""), 3),
        Signal(CardLanguage.ENGLISH, Regex("""\bretreat\b"""), 4),
        Signal(CardLanguage.FRENCH, Regex("""\bevolue\s+de\b"""), 6),
        Signal(CardLanguage.FRENCH, Regex("""\bniveau\b"""), 3),
        Signal(CardLanguage.FRENCH, Regex("""\bfaiblesse\b"""), 4),
        Signal(CardLanguage.FRENCH, Regex("""\bresistance\b"""), 3),
        Signal(CardLanguage.FRENCH, Regex("""\bretraite\b"""), 4),
        Signal(CardLanguage.GERMAN, Regex("""\bentwickelt\s+sich\s+aus\b"""), 6),
        Signal(CardLanguage.GERMAN, Regex("""\bschwache\b"""), 4),
        Signal(CardLanguage.GERMAN, Regex("""\bresistenz\b"""), 4),
        Signal(CardLanguage.GERMAN, Regex("""\bruckzug\b"""), 4),
        Signal(CardLanguage.SPANISH, Regex("""\bevoluciona\s+de\b"""), 6),
        Signal(CardLanguage.SPANISH, Regex("""\bdebilidad\b"""), 4),
        Signal(CardLanguage.SPANISH, Regex("""\bresistencia\b"""), 3),
        Signal(CardLanguage.SPANISH, Regex("""\bretirada\b"""), 3),
        Signal(CardLanguage.PORTUGUESE, Regex("""\bevolui\s+de\b"""), 6),
        Signal(CardLanguage.PORTUGUESE, Regex("""\bfraqueza\b"""), 4),
        Signal(CardLanguage.PORTUGUESE, Regex("""\bresistencia\b"""), 3),
        Signal(CardLanguage.PORTUGUESE, Regex("""\brecuo\b"""), 4),
        Signal(CardLanguage.JAPANESE, Regex("""にげる"""), 6),
        Signal(CardLanguage.JAPANESE, Regex("""ワザ"""), 3),
        Signal(CardLanguage.KOREAN, Regex("""약점"""), 6),
        Signal(CardLanguage.KOREAN, Regex("""후퇴"""), 4),
        Signal(CardLanguage.CHINESE_T, Regex("""弱點"""), 6),
        Signal(CardLanguage.CHINESE_S, Regex("""弱点"""), 6),
    )

    private fun pickCardText(raw: String?): CardLanguage? {
        val original = raw?.trim().orEmpty()
        if (original.length < 3) return null
        val folded = fold(original)
        val scores = mutableMapOf<CardLanguage, Int>()
        for (signal in pokemonSignals) {
            val haystack = if (signal.pattern.pattern.all { it.code < 0x250 || it == '\\' || it in "b+*?.()|" }) {
                folded
            } else {
                original
            }
            if (signal.pattern.containsMatchIn(haystack)) {
                scores[signal.language] = (scores[signal.language] ?: 0) + signal.weight
            }
        }
        scriptScore(original)?.let { (language, weight) ->
            scores[language] = (scores[language] ?: 0) + weight
        }
        val best = scores.maxByOrNull { it.value } ?: return null
        val runner = scores.filterKeys { it != best.key }.maxOfOrNull { it.value } ?: 0
        if (best.value < 3) return null
        if (runner > 0 && best.value - runner < 2) return null
        return best.key
    }

    private fun scriptScore(text: String): Pair<CardLanguage, Int>? {
        val hangul = text.count { it in '\uAC00'..'\uD7A3' || it in '\u1100'..'\u11FF' }
        val kana = text.count { it in '\u3040'..'\u30FF' || it == 'ー' }
        val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val latin = text.count { it.isLetter() && it.code < 0x250 }
        if (hangul >= 2 && hangul >= kana && hangul >= latin) return CardLanguage.KOREAN to 8
        if (kana >= 2 && kana >= latin) return CardLanguage.JAPANESE to 8
        if (han >= 2 && kana == 0 && hangul == 0 && han >= latin) {
            val traditional = text.count { it in TRADITIONAL_HINTS }
            val simplified = text.count { it in SIMPLIFIED_HINTS }
            return when {
                traditional > simplified && traditional > 0 -> CardLanguage.CHINESE_T to 8
                simplified > traditional && simplified > 0 -> CardLanguage.CHINESE_S to 8
                else -> null
            }
        }
        return null
    }

    private fun fold(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return decomposed.lowercase(Locale.ROOT)
    }

    private const val TRADITIONAL_HINTS = "點國龍電擊對這個屬傷無與為開關戰鬥寶夢術強網線體"
    private const val SIMPLIFIED_HINTS = "点国龙电击对这个属伤无与为开关战斗宝梦术强网线体"

    private const val MIN_SCORE = 0.75f
    private const val MIN_GAP = 0.15f
}
