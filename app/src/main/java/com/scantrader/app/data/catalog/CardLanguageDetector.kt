package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.CardLanguage
import java.util.Locale

/**
 * Picks the confirm-screen language.
 * Detection compares the OCR title with the English oracle name and each
 * localized printed name. A tie or a weak score falls back to the last choice.
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
    ): CardLanguage? {
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
        return best.first
    }

    fun normalize(raw: String?): String? {
        val text = raw?.trim()?.lowercase(Locale.ROOT)
            ?.replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
        return text?.takeIf { it.length >= 3 }
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

    private const val MIN_SCORE = 0.75f
    private const val MIN_GAP = 0.15f
}
