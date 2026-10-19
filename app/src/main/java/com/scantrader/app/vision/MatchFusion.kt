package com.scantrader.app.vision

import com.scantrader.app.data.catalog.PokemonCatalog
import com.scantrader.app.data.catalog.PokemonSets
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.OcrParser

/**
 * Fuses OCR catalog signals with on-device visual similarity to minimise wrong picks.
 */
object MatchFusion {
    /** Auto-scan accepts only when fused confidence clears this bar. */
    const val AUTO_CONFIDENCE_MIN = 0.74f

    fun fuse(candidates: List<IdentifiedCard>, parsed: ParsedCardText): List<IdentifiedCard> {
        if (candidates.isEmpty()) return emptyList()
        return candidates
            .map { card -> score(card, parsed) }
            .sortedWith(
                compareByDescending<IdentifiedCard> { it.matchConfidence ?: 0f }
                    .thenByDescending { it.visualScore ?: 0f }
                    .thenByDescending { it.ocrScore ?: 0f },
            )
    }

    fun score(card: IdentifiedCard, parsed: ParsedCardText): IdentifiedCard {
        val ocr = card.ocrScore ?: ocrAgreement(card, parsed)
        val visual = card.visualScore
        val base = when {
            // Strong neural-gallery hit: trust vision even if OCR is weak/empty.
            visual != null && visual >= 0.88f -> 0.82f * visual + 0.18f * ocr
            visual != null -> 0.58f * visual + 0.42f * ocr
            ocr >= 0.95f -> ocr
            else -> ocr * 0.88f
        }
        val agreementBonus = when {
            visual != null && visual >= 0.78f && ocr >= 0.75f -> 0.10f
            visual != null && visual >= 0.70f && ocr >= 0.85f -> 0.07f
            visual != null && visual >= 0.65f && ocr >= 0.60f -> 0.03f
            else -> 0f
        }
        // Penalise strong OCR/visual disagreement (typical wrong-print trap).
        // Skip penalty when neural score is decisive (gallery reverse-image).
        val conflictPenalty = when {
            visual != null && visual >= 0.88f -> 0f
            visual != null && visual < 0.45f && ocr >= 0.80f -> 0.12f
            visual != null && visual >= 0.80f && ocr < 0.35f -> 0.05f
            else -> 0f
        }
        return card.copy(
            ocrScore = ocr,
            matchConfidence = (base + agreementBonus - conflictPenalty).coerceIn(0f, 1f),
        )
    }

    /**
     * Strict gate for hands-free auto-scan: prefer visual confirmation.
     */
    fun isConfidentAutoMatch(top: IdentifiedCard?): Boolean {
        if (top == null) return false
        val confidence = top.matchConfidence ?: return false
        if (confidence < AUTO_CONFIDENCE_MIN) return false
        val visual = top.visualScore
        val ocr = top.ocrScore ?: 0f
        return when {
            visual != null && visual >= 0.88f && confidence >= 0.78f -> true
            visual != null && visual >= 0.80f && confidence >= AUTO_CONFIDENCE_MIN -> true
            visual != null && visual >= 0.72f && ocr >= 0.80f -> true
            // Exact set+number OCR without usable art still allowed if very strong.
            visual == null && ocr >= 0.95f && confidence >= 0.90f -> true
            else -> false
        }
    }

    fun ocrAgreement(card: IdentifiedCard, parsed: ParsedCardText): Float {
        var score = 0f
        var weight = 0f

        val numbers = OcrParser.collectorCandidates(parsed.collectorNumber)
        if (numbers.isNotEmpty()) {
            weight += 0.40f
            val cn = card.collectorNumber.trimStart('0').ifBlank { card.collectorNumber }
            val hit = numbers.any {
                card.collectorNumber.equals(it, ignoreCase = true) ||
                    cn.equals(it.trimStart('0').ifBlank { it }, ignoreCase = true)
            }
            if (hit) score += 0.40f
        }

        val sets = OcrParser.setCodeCandidates(parsed.setCode)
        if (sets.isNotEmpty()) {
            weight += 0.35f
            val cardSet = card.setCode.lowercase()
            val hit = if (card.game == Game.POKEMON) {
                sets.any { set -> PokemonSets.sameSet(set, card.setCode) }
            } else {
                sets.any { set ->
                    cardSet == set || cardSet.startsWith(set) || set.startsWith(cardSet)
                }
            }
            if (hit) score += 0.35f
        }

        val name = parsed.nameGuess?.trim().orEmpty()
        if (name.length >= 2) {
            weight += 0.25f
            if (card.game == Game.POKEMON && PokemonCatalog.nameMatches(card.name, name)) {
                score += 0.25f
            } else {
                val tokens = name.lowercase()
                    .split(Regex("""[^a-z0-9àèéìòù]+"""))
                    .filter { it.length >= 3 }
                val cardName = card.name.lowercase()
                val overlap = tokens.count { token -> cardName.contains(token) }
                if (tokens.isNotEmpty()) {
                    score += 0.25f * (overlap.toFloat() / tokens.size)
                }
            }
        }

        if (weight <= 0f) return card.ocrScore ?: 0.2f
        return (score / weight).coerceIn(0f, 1f)
    }
}
