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
    data class Detection(
        val language: CardLanguage?,
        val evidence: String,
    ) {
        /** Logcat message. The tag is [com.scantrader.app.scan.ScanDebug.TAG], so the filter is `ScanTrader lang:`. */
        fun logLine(): String {
            val picked = language?.label ?: "uncertain"
            return "lang: $picked evidence=${evidence.ifBlank { "none" }}"
        }
    }

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
    ): CardLanguage? = explain(ocrName, englishName, printed, ocrText, pokemonSignals).language

    /**
     * Pokémon rules text wins when the printed name is the same in every language
     * we know (Machop, Pikachu). A title that differs across languages still wins.
     */
    fun explain(
        ocrName: String?,
        englishName: String?,
        printed: Map<CardLanguage, String>,
        ocrText: String? = null,
        pokemonSignals: Boolean = false,
    ): Detection {
        val named = pickName(ocrName, englishName, printed)
        val cardText = if (pokemonSignals) {
            scoreCardText(listOfNotNull(ocrText, ocrName).filter { it.isNotBlank() }.joinToString("\n"))
        } else {
            null
        }
        val nameProvesLanguage = named != null &&
            (!pokemonSignals || nameDistinguishes(ocrName, englishName, printed, named))
        val proven = named?.takeIf { nameProvesLanguage }
        val language = when {
            cardText?.language != null && proven == null -> cardText.language
            proven != null -> proven.language
            else -> null
        }
        val evidence = buildList {
            val labels = when {
                language != null && cardText?.language == language -> cardText.labels
                language == null -> cardText?.every.orEmpty()
                else -> emptyList()
            }
            addAll(labels)
            when {
                pokemonSignals && proven == null -> add("name=shared")
                proven != null -> add("name=${proven.language.scryfallLang()}")
            }
        }.distinct().joinToString(",")
        return Detection(language, evidence)
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

    /**
     * True when one language's title matches the OCR and another known title does not.
     * A single shared species name (only "Machop", or Italian and English both "Machop")
     * does not prove the print language.
     */
    private fun nameDistinguishes(
        ocrName: String?,
        englishName: String?,
        printed: Map<CardLanguage, String>,
        named: NamePick,
    ): Boolean {
        if (!named.exact) return false
        val ocr = normalize(ocrName) ?: return false
        val titles = buildList {
            normalize(englishName)?.let { add(it) }
            printed.values.forEach { title -> normalize(title)?.let { add(it) } }
        }.distinct()
        if (titles.isEmpty()) return false
        if (titles.size == 1 && titles.first() == ocr) return false
        return titles.any { it != ocr }
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

    private data class Signal(
        val language: CardLanguage,
        val pattern: Regex,
        val weight: Int,
        val label: String,
    )

    private val pokemonSignals: List<Signal> = listOf(
        Signal(CardLanguage.ITALIAN, Regex("""\bsi\s*evol\w{0,8}\s+da\b"""), 6, "si evolve da"),
        Signal(CardLanguage.ITALIAN, Regex("""\bsi\s*eval\w{0,8}\s+da\b"""), 6, "si evolve da"),
        Signal(CardLanguage.ITALIAN, Regex("""\bsievo\w{0,8}da\b"""), 6, "si evolve da"),
        Signal(CardLanguage.ITALIAN, Regex("""\bdebole\w{0,3}za\b"""), 4, "debolezza"),
        Signal(CardLanguage.ITALIAN, Regex("""\bresistenza\b"""), 3, "resistenza"),
        Signal(CardLanguage.ITALIAN, Regex("""\bcosto\s+di\s+ritirata\b"""), 6, "costo di ritirata"),
        Signal(CardLanguage.ITALIAN, Regex("""\britirata\b"""), 2, "ritirata"),
        Signal(CardLanguage.ITALIAN, Regex("""\blunghezza\b"""), 4, "lunghezza"),
        Signal(CardLanguage.ITALIAN, Regex("""\bpeso\b"""), 2, "peso"),
        Signal(CardLanguage.ITALIAN, Regex("""\bpokemon\s+base\b"""), 4, "pokemon base"),
        Signal(CardLanguage.ITALIAN, Regex("""\b\d{1,3}\s*pv\b"""), 3, "pv"),
        Signal(CardLanguage.ITALIAN, Regex("""\bfase\w{0,2}\b"""), 2, "fase"),
        Signal(CardLanguage.ENGLISH, Regex("""\bevolves?\s+from\b"""), 6, "evolves from"),
        Signal(CardLanguage.ENGLISH, Regex("""\bweakness\b"""), 4, "weakness"),
        Signal(CardLanguage.ENGLISH, Regex("""\bresistance\b"""), 3, "resistance"),
        Signal(CardLanguage.ENGLISH, Regex("""\bretreat\b"""), 4, "retreat"),
        Signal(CardLanguage.ENGLISH, Regex("""\b\d{1,3}\s*hp\b"""), 3, "hp"),
        Signal(CardLanguage.FRENCH, Regex("""\bevolue\s+de\b"""), 6, "evolue de"),
        Signal(CardLanguage.FRENCH, Regex("""\bniveau\b"""), 3, "niveau"),
        Signal(CardLanguage.FRENCH, Regex("""\bfaiblesse\b"""), 4, "faiblesse"),
        Signal(CardLanguage.FRENCH, Regex("""\bresistance\b"""), 3, "resistance"),
        Signal(CardLanguage.FRENCH, Regex("""\bretraite\b"""), 4, "retraite"),
        Signal(CardLanguage.FRENCH, Regex("""\b\d{1,3}\s*pv\b"""), 3, "pv"),
        Signal(CardLanguage.GERMAN, Regex("""\bentwickelt\s+sich\s+aus\b"""), 6, "entwickelt sich aus"),
        Signal(CardLanguage.GERMAN, Regex("""\bschwache\b"""), 4, "schwache"),
        Signal(CardLanguage.GERMAN, Regex("""\bresistenz\b"""), 4, "resistenz"),
        Signal(CardLanguage.GERMAN, Regex("""\bruckzug\b"""), 4, "ruckzug"),
        Signal(CardLanguage.SPANISH, Regex("""\bevoluciona\s+de\b"""), 6, "evoluciona de"),
        Signal(CardLanguage.SPANISH, Regex("""\bdebilidad\b"""), 4, "debilidad"),
        Signal(CardLanguage.SPANISH, Regex("""\bresistencia\b"""), 3, "resistencia"),
        Signal(CardLanguage.SPANISH, Regex("""\bretirada\b"""), 3, "retirada"),
        Signal(CardLanguage.PORTUGUESE, Regex("""\bevolui\s+de\b"""), 6, "evolui de"),
        Signal(CardLanguage.PORTUGUESE, Regex("""\bfraqueza\b"""), 4, "fraqueza"),
        Signal(CardLanguage.PORTUGUESE, Regex("""\bresistencia\b"""), 3, "resistencia"),
        Signal(CardLanguage.PORTUGUESE, Regex("""\brecuo\b"""), 4, "recuo"),
        Signal(CardLanguage.JAPANESE, Regex("""にげる"""), 6, "にげる"),
        Signal(CardLanguage.JAPANESE, Regex("""ワザ"""), 3, "ワザ"),
        Signal(CardLanguage.KOREAN, Regex("""약점"""), 6, "약점"),
        Signal(CardLanguage.KOREAN, Regex("""후퇴"""), 4, "후퇴"),
        Signal(CardLanguage.CHINESE_T, Regex("""弱點"""), 6, "弱點"),
        Signal(CardLanguage.CHINESE_S, Regex("""弱点"""), 6, "弱点"),
    )

    private data class TextScore(
        val language: CardLanguage?,
        val labels: List<String>,
        val every: List<String>,
    )

    private fun scoreCardText(raw: String?): TextScore? {
        val original = raw?.trim().orEmpty()
        if (original.length < 3) return null
        val folded = fold(original)
        val scores = mutableMapOf<CardLanguage, Int>()
        val labels = mutableMapOf<CardLanguage, MutableList<String>>()
        for (signal in pokemonSignals) {
            val haystack = if (signal.pattern.pattern.all { it.code < 0x250 || it == '\\' || it in "b+*?.()|" }) {
                folded
            } else {
                original
            }
            if (signal.pattern.containsMatchIn(haystack)) {
                scores[signal.language] = (scores[signal.language] ?: 0) + signal.weight
                val found = labels.getOrPut(signal.language) { mutableListOf() }
                if (signal.label !in found) found += signal.label
            }
        }
        scriptScore(original)?.let { (language, weight) ->
            scores[language] = (scores[language] ?: 0) + weight
            val found = labels.getOrPut(language) { mutableListOf() }
            if ("script" !in found) found += "script"
        }
        val every = labels.values.flatten().distinct()
        val best = scores.maxByOrNull { it.value } ?: return TextScore(null, emptyList(), every)
        val runner = scores.filterKeys { it != best.key }.maxOfOrNull { it.value } ?: 0
        if (best.value < 3 || (runner > 0 && best.value - runner < 2)) {
            return TextScore(null, emptyList(), every)
        }
        return TextScore(best.key, labels[best.key].orEmpty(), every)
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
