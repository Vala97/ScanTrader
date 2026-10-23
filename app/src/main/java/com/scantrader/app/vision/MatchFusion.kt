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
     * Soft floors for auto-scan without a decisive neural hit.
     * Confirm screen is the safety net — better to open confirm than loop forever.
     */
    const val POKEMON_AUTO_CONFIDENCE_MIN = 0.48f
    const val MTG_AUTO_CONFIDENCE_MIN = 0.52f

    /** Result of the auto-scan confidence gate (for logcat diagnostics). */
    data class AutoMatchDecision(
        val accepted: Boolean,
        val reason: String,
        val nameOk: Boolean = false,
        val nameConflict: Boolean = false,
        val cnOk: Boolean = false,
        val countOk: Boolean = false,
        val setOk: Boolean = false,
        val shortCn: Boolean = false,
        val confidence: Float? = null,
    )

    /**
     * Gate for hands-free auto-scan.
     * Strict neural/OCR path first; then game-specific soft paths so auto-scan
     * does not loop forever when the neural pack is missing.
     * Pokémon: never auto-accept when OCR name contradicts the top hit.
     * MTG: set+collector is language-safe; Italian OCR vs English Scryfall name
     * is not treated as a hard conflict when set+cn align.
     */
    fun isConfidentAutoMatch(top: IdentifiedCard?, parsed: ParsedCardText? = null): Boolean =
        evaluateAutoMatch(top, parsed).accepted

    fun evaluateAutoMatch(top: IdentifiedCard?, parsed: ParsedCardText? = null): AutoMatchDecision {
        if (top == null) {
            return AutoMatchDecision(accepted = false, reason = "noTop")
        }
        val confidence = top.matchConfidence
            ?: return AutoMatchDecision(accepted = false, reason = "noConf")
        val visual = top.visualScore
        val ocr = top.ocrScore ?: 0f
        val strictOk = confidence >= AUTO_CONFIDENCE_MIN && when {
            visual != null && visual >= 0.88f && confidence >= 0.78f -> true
            visual != null && visual >= 0.80f && confidence >= AUTO_CONFIDENCE_MIN -> true
            visual != null && visual >= 0.72f && ocr >= 0.80f -> true
            // Exact set+number OCR without usable art still allowed if very strong.
            visual == null && ocr >= 0.95f && confidence >= 0.90f -> true
            else -> false
        }
        if (strictOk) {
            return AutoMatchDecision(
                accepted = true,
                reason = "strict",
                confidence = confidence,
            )
        }
        if (parsed == null) {
            return AutoMatchDecision(
                accepted = false,
                reason = "strictFail",
                confidence = confidence,
            )
        }
        return when (top.game) {
            Game.POKEMON -> evaluatePokemonSoft(top, parsed, confidence, visual, ocr)
            Game.MTG -> evaluateMtgSoft(top, parsed, confidence, visual, ocr)
        }
    }

    private fun evaluatePokemonSoft(
        top: IdentifiedCard,
        parsed: ParsedCardText,
        confidence: Float,
        visual: Float?,
        ocr: Float,
    ): AutoMatchDecision {
        if (confidence < POKEMON_AUTO_CONFIDENCE_MIN) {
            return AutoMatchDecision(
                accepted = false,
                reason = "confLow",
                confidence = confidence,
            )
        }
        val visualOk = visual == null || visual >= 0.42f
        if (!visualOk) {
            return AutoMatchDecision(
                accepted = false,
                reason = "visualLow",
                confidence = confidence,
            )
        }

        val nameGuess = parsed.nameGuess?.trim().orEmpty()
        val namePresent = nameGuess.length >= 2
        val nameOk = namePresent && PokemonCatalog.nameMatches(top.name, nameGuess)
        val nameConflict = namePresent && !nameOk
        val numbers = OcrParser.collectorCandidates(parsed.collectorNumber)
        val cn = top.collectorNumber.trimStart('0').ifBlank { top.collectorNumber }
        val cnOk = numbers.any {
            top.collectorNumber.equals(it, ignoreCase = true) ||
                cn.equals(it.trimStart('0').ifBlank { it }, ignoreCase = true)
        }
        val countOk = parsed.setCardCount != null &&
            PokemonSets.idsForCardCount(parsed.setCardCount)
                .any { PokemonSets.sameSet(it, top.setCode) }
        val setOk = OcrParser.setCodeCandidates(parsed.setCode).any { set ->
            PokemonSets.sameSet(set, top.setCode)
        }
        val cnSignificantDigits = numbers
            .map { it.filter(Char::isDigit).trimStart('0').ifBlank { "0" }.length }
            .maxOrNull() ?: 0
        val shortCn = cnSignificantDigits in 1..2
        // Short collectors (69 vs 169) need stronger OCR/visual when name is absent.
        val idStrongEnough = !shortCn || ocr >= 0.55f || (visual ?: 0f) >= 0.55f

        if (nameConflict) {
            return AutoMatchDecision(
                accepted = false,
                reason = "nameConflict name=$nameGuess top=${top.name}",
                nameOk = false,
                nameConflict = true,
                cnOk = cnOk,
                countOk = countOk,
                setOk = setOk,
                shortCn = shortCn,
                confidence = confidence,
            )
        }
        val softReason = when {
            nameOk -> "nameOk"
            cnOk && countOk && idStrongEnough -> "cn+count"
            cnOk && ocr >= 0.30f && idStrongEnough -> "cn+ocr"
            confidence >= 0.55f && (visual ?: 0f) >= 0.52f && ocr >= 0.18f -> "conf+visual"
            cnOk && countOk && !idStrongEnough -> "cnShortWeak"
            else -> "softFail"
        }
        val accepted = softReason in setOf("nameOk", "cn+count", "cn+ocr", "conf+visual")
        return AutoMatchDecision(
            accepted = accepted,
            reason = softReason,
            nameOk = nameOk,
            nameConflict = false,
            cnOk = cnOk,
            countOk = countOk,
            setOk = setOk,
            shortCn = shortCn,
            confidence = confidence,
        )
    }

    private fun evaluateMtgSoft(
        top: IdentifiedCard,
        parsed: ParsedCardText,
        confidence: Float,
        visual: Float?,
        ocr: Float,
    ): AutoMatchDecision {
        if (confidence < MTG_AUTO_CONFIDENCE_MIN) {
            return AutoMatchDecision(
                accepted = false,
                reason = "confLow",
                confidence = confidence,
            )
        }
        val visualOk = visual == null || visual >= 0.40f
        if (!visualOk) {
            return AutoMatchDecision(
                accepted = false,
                reason = "visualLow",
                confidence = confidence,
            )
        }

        val nameGuess = parsed.nameGuess?.trim().orEmpty()
        val namePresent = nameGuess.length >= 2
        val nameOk = namePresent && mtgNameMatches(top.name, nameGuess)
        val numbers = OcrParser.collectorCandidates(parsed.collectorNumber)
        val cn = top.collectorNumber.trimStart('0').ifBlank { top.collectorNumber }
        val cnOk = numbers.any {
            top.collectorNumber.equals(it, ignoreCase = true) ||
                cn.equals(it.trimStart('0').ifBlank { it }, ignoreCase = true)
        }
        val sets = OcrParser.setCodeCandidates(parsed.setCode)
        val cardSet = top.setCode.lowercase()
        val setOk = sets.any { set ->
            cardSet == set || cardSet.startsWith(set) || set.startsWith(cardSet)
        }

        val nameConflict = namePresent && !nameOk && !looksLikeMtgRulesNoise(nameGuess)
        // Italian OCR title vs English Scryfall name is common. Never trust set+cn alone
        // when the OCR title clearly disagrees (e.g. Divinatrice → Emrakul from +1/+1→cn=1).
        if (nameConflict) {
            // Name-search hits score ~0.88; set+cn wrong-card hits score ~0.98.
            // Only trust the name-search band so IT title → EN Scryfall name is accepted,
            // while Divinatrice→Emrakul (cn mistook +1/+1) stays rejected.
            val fromNameSearch = ocr >= 0.85f && ocr < 0.95f
            val softReason = when {
                fromNameSearch && confidence >= 0.58f -> "scryfallName"
                cnOk && setOk && ocr >= 0.95f && (visual ?: 0f) >= 0.70f ->
                    "cn+set+visualStrong"
                else -> "nameConflict name=$nameGuess top=${top.name}"
            }
            val accepted = softReason == "scryfallName" || softReason == "cn+set+visualStrong"
            return AutoMatchDecision(
                accepted = accepted,
                reason = softReason,
                nameOk = false,
                nameConflict = true,
                cnOk = cnOk,
                setOk = setOk,
                confidence = confidence,
            )
        }
        val softReason = when {
            cnOk && setOk && ocr >= 0.55f -> "cn+set"
            cnOk && setOk && confidence >= 0.70f -> "cn+set"
            nameOk && cnOk && setOk -> "name+cn+set"
            nameOk && cnOk && ocr >= 0.65f -> "name+cn"
            nameOk && setOk && confidence >= 0.58f && ocr >= 0.50f -> "name+set"
            nameOk && ocr >= 0.80f && confidence >= 0.55f -> "nameExact"
            nameOk && (visual ?: 0f) >= 0.55f && confidence >= 0.55f && ocr >= 0.50f -> "name+visual"
            cnOk && setOk && (visual ?: 0f) >= 0.60f && ocr >= 0.50f -> "cn+set+visual"
            else -> "softFail"
        }
        val accepted = softReason != "softFail"
        return AutoMatchDecision(
            accepted = accepted,
            reason = softReason,
            nameOk = nameOk,
            nameConflict = false,
            cnOk = cnOk,
            setOk = setOk,
            confidence = confidence,
        )
    }

    private fun looksLikeMtgRulesNoise(name: String): Boolean =
        Regex(
            """(?i)\b(?:if|exile|target|creature|battlefield|mana|wizards|mtgstory)\b""",
        ).containsMatchIn(name) || name.trim().length < 4

    /** Loose token overlap for EN/IT MTG titles (not Pokémon species matching). */
    private fun mtgNameMatches(cardName: String, query: String): Boolean {
        val tokens = query.lowercase()
            .split(Regex("""[^a-z0-9àèéìòù]+"""))
            .filter { it.length >= 3 }
        if (tokens.isEmpty()) return false
        val card = cardName.lowercase()
        val hits = tokens.count { token -> card.contains(token) }
        return hits >= 1 && hits.toFloat() / tokens.size >= 0.4f
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
            val nameHit = if (card.game == Game.POKEMON) {
                PokemonCatalog.nameMatches(card.name, name)
            } else {
                val tokens = name.lowercase()
                    .split(Regex("""[^a-z0-9àèéìòù]+"""))
                    .filter { it.length >= 3 }
                val cardName = card.name.lowercase()
                tokens.isNotEmpty() && tokens.count { token -> cardName.contains(token) } > 0
            }
            if (nameHit) {
                score += 0.25f
            } else if (card.game == Game.POKEMON) {
                // No name overlap → pull confidence down even if number matched.
                score -= 0.20f
            }
        }

        if (parsed.gameHintIsPokemonSetCount() && card.game == Game.POKEMON) {
            // setCardCount agreement is handled via ocrScore from CatalogLookup;
            // boost fusion when OCR printed total is present and set already matched.
            val sets = OcrParser.setCodeCandidates(parsed.setCode)
            if (sets.isEmpty() && parsed.setCardCount != null) {
                weight += 0.15f
                if (PokemonSets.idsForCardCount(parsed.setCardCount)
                        .any { PokemonSets.sameSet(it, card.setCode) }
                ) {
                    score += 0.15f
                }
            }
        }

        if (weight <= 0f) return card.ocrScore ?: 0.2f
        return (score / weight).coerceIn(0f, 1f)
    }

    private fun ParsedCardText.gameHintIsPokemonSetCount(): Boolean = setCardCount != null
}
