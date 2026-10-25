package com.scantrader.app.scan

import com.scantrader.app.data.catalog.CardIdentity
import com.scantrader.app.data.catalog.PokemonCatalog
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText

/**
 * A catalog hit may be shown or auto-saved only when its name agrees with the
 * frame that triggered acceptance. A stale Abra lookup must not survive a Machop read.
 */
object FrameNameGate {
    /** A real title. Stage crumbs and the supporter label `Aiuto` do not count. */
    fun confidentTitle(name: String?): String? {
        val trimmed = name?.trim()?.takeIf { it.length >= 3 } ?: return null
        if (CardIdentity.isStageNoise(trimmed) || OcrParser.isTrainerLayoutWord(trimmed)) return null
        return trimmed
    }

    /** True when [frameName] is a real title and does not match [otherName]. */
    fun disagrees(frameName: String?, otherName: String?): Boolean {
        val frame = confidentTitle(frameName) ?: return false
        if (PokemonCatalog.nameMatches(otherName, frame)) return false
        return !CardIdentity.namesSimilar(frame, otherName)
    }

    fun keep(cards: List<IdentifiedCard>, frame: ParsedCardText): List<IdentifiedCard> {
        if (confidentTitle(frame.nameGuess) == null) return cards
        return cards.filter { card -> agrees(card, frame) }
    }

    fun agrees(card: IdentifiedCard, frame: ParsedCardText): Boolean {
        val frameName = confidentTitle(frame.nameGuess) ?: return true
        if (agreesName(card.game, card.name, card.printedName, frameName)) return true
        // Italian OCR vs the English gallery title is the same card when the
        // collector matches. An English title that names a different card
        // (Ravening Warg vs Elrond, no gallery score) stays a conflict.
        return foreignGalleryAgrees(card, frame, frameName)
    }

    /** Fuzzy title check. An id hit whose name disagrees is not a match. */
    fun agreesName(game: Game, cardName: String?, printedName: String?, ocrName: String): Boolean {
        if (game == Game.POKEMON) {
            return PokemonCatalog.nameMatches(cardName, ocrName) ||
                PokemonCatalog.nameMatches(printedName, ocrName)
        }
        val titles = faceTitles(cardName) + faceTitles(printedName)
        return titles.any { title ->
            CardIdentity.namesSimilar(title, ocrName) || partialMtgTitle(title, ocrName)
        }
    }

    /** Each face of an adventure or split card (`Name // Other`). */
    fun faceTitles(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val parts = raw.split(Regex("""\s*//\s*""")).map { it.trim() }.filter { it.isNotBlank() }
        return if (parts.size <= 1) listOf(raw.trim()) else parts
    }

    /**
     * `Maestro di Spada Invaghi` is not an English title. When the gallery
     * already picked this collector, that OCR is not a different card.
     */
    private fun foreignGalleryAgrees(
        card: IdentifiedCard,
        frame: ParsedCardText,
        frameName: String,
    ): Boolean {
        if (card.game != Game.MTG) return false
        val visual = card.visualScore ?: return false
        // Same floor as GalleryMatcher.MIN_SCORE. Below it the hit is not the gallery top.
        if (visual < 0.55f) return false
        if (!looksLikeForeignTitle(frameName)) return false
        return collectorMatches(card.collectorNumber, frame.collectorNumber)
    }

    /** Italian / other printed-language function words, or an accented letter. */
    fun looksLikeForeignTitle(name: String): Boolean {
        if (name.any { it.isLetter() && it.code > 127 }) return true
        val tokens = name.lowercase().split(Regex("""[^a-zà-ÿ]+""")).filter { it.isNotBlank() }
        return tokens.any { it in FOREIGN_PARTICLES }
    }

    private fun collectorMatches(cardNumber: String, parsedNumber: String?): Boolean {
        val numbers = OcrParser.collectorCandidates(parsedNumber)
        if (numbers.isEmpty()) return false
        val cn = cardNumber.trimStart('0').ifBlank { cardNumber }
        return numbers.any {
            cardNumber.equals(it, ignoreCase = true) ||
                cn.equals(it.trimStart('0').ifBlank { it }, ignoreCase = true)
        }
    }

    private val FOREIGN_PARTICLES = setOf(
        "di", "del", "della", "dello", "dei", "delle", "degli",
        "il", "lo", "la", "gli", "le", "un", "una", "uno",
        "al", "allo", "alla", "nel", "nella", "sul", "sulla",
        "von", "der", "und", "des", "les", "une", "aux", "los", "las",
    )

    /**
     * `Goblir` / `Goblin-town` against "Down, Down to Goblin-town".
     * A short rules fragment (`nchar`) does not match.
     */
    fun partialMtgTitle(cardName: String?, ocrName: String?): Boolean {
        val query = titleTokens(ocrName).filter { it.length >= 5 }
        val card = titleTokens(cardName)
        if (query.isEmpty() || card.isEmpty()) return false
        return query.all { token -> card.any { other -> tokenClose(token, other) } }
    }

    private fun titleTokens(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .split(" ")
            .filter { it.length >= 3 }
    }

    private fun tokenClose(left: String, right: String): Boolean {
        if (left == right) return true
        val shorter = if (left.length <= right.length) left else right
        val longer = if (left.length <= right.length) right else left
        if (shorter.length >= 5 && longer.contains(shorter)) return true
        return CardIdentity.similarity(left, right) >= 0.75
    }
}
