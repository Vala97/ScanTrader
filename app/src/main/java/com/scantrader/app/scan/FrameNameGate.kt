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
        if (extraTitleWord(frame, otherName)) return true
        if (PokemonCatalog.nameMatches(otherName, frame)) return false
        if (mtgTitlesAgree(otherName, frame) || mtgTitlesAgree(frame, otherName)) return false
        return !CardIdentity.namesSimilar(frame, otherName)
    }

    /** `Mirkwood` and `Mirkwood Meditator` are different cards. A Pokémon `VMAX` is not. */
    private fun extraTitleWord(frame: String, other: String?): Boolean {
        val left = CardIdentity.titleWords(frame)
        val right = CardIdentity.titleWords(other.orEmpty())
        if (left.isEmpty() || right.isEmpty() || left.size == right.size) return false
        val short = if (left.size < right.size) left else right
        val long = if (left.size < right.size) right else left
        if (!short.all { token -> long.any { CardIdentity.wordClose(token, it) } }) return false
        if (hasForeignParticle(frame) || hasForeignParticle(other.orEmpty())) return false
        val extras = long.filter { token -> short.none { CardIdentity.wordClose(token, it) } }
        return extras.any { it !in POKEMON_VARIANTS }
    }

    private fun hasForeignParticle(raw: String): Boolean {
        val tokens = raw.lowercase().split(Regex("""[^a-zà-ÿ]+""")).filter { it.isNotBlank() }
        return tokens.any { it in FOREIGN_PARTICLES }
    }

    private val POKEMON_VARIANTS = setOf("ex", "gx", "vmax", "vstar", "lvx", "break")

    fun keep(cards: List<IdentifiedCard>, frame: ParsedCardText): List<IdentifiedCard> {
        if (confidentTitle(frame.nameGuess) == null) return cards
        return cards.filter { card -> agrees(card, frame) }
    }

    fun agrees(card: IdentifiedCard, frame: ParsedCardText): Boolean {
        val frameName = confidentTitle(frame.nameGuess) ?: return true
        if (agreesName(card.game, card.name, card.printedName, frameName, card.catalogName)) return true
        if (card.game == Game.MTG && collectorMatches(card.collectorNumber, frame.collectorNumber) &&
            (mtgTitlesAgree(card.name, frameName, allowTruncatedTitle = true) ||
                mtgTitlesAgree(card.printedName, frameName, allowTruncatedTitle = true) ||
                mtgTitlesAgree(card.catalogName, frameName, allowTruncatedTitle = true))
        ) {
            return true
        }
        // Italian OCR vs the English gallery title is the same card when the
        // collector matches. An English title that names a different card
        // (Ravening Warg vs Elrond, no gallery score) stays a conflict.
        return foreignGalleryAgrees(card, frame, frameName)
    }

    /**
     * Fuzzy title check. An id hit whose name disagrees is not a match.
     * Oracle [cardName], printed title, and [catalogName] are the same card
     * when the normalized text is equal (`Meadowboon` vs `Meadowboon`).
     */
    fun agreesName(
        game: Game,
        cardName: String?,
        printedName: String?,
        ocrName: String,
        catalogName: String? = null,
    ): Boolean {
        if (game == Game.POKEMON) {
            return PokemonCatalog.nameMatches(cardName, ocrName) ||
                PokemonCatalog.nameMatches(printedName, ocrName) ||
                PokemonCatalog.nameMatches(catalogName, ocrName)
        }
        val query = CardIdentity.normalizeName(ocrName)
        val titles = faceTitles(cardName) + faceTitles(printedName) + faceTitles(catalogName)
        return titles.any { title ->
            val norm = CardIdentity.normalizeName(title)
            (query.isNotBlank() && query == norm) ||
                mtgTitlesAgree(title, ocrName, allowTruncatedTitle = false)
        }
    }

    /**
     * Magic titles. Equal words, or one OCR word cut short (`Squadr` / `Squadra`).
     * `Mirkwood` is not `Mirkwood Meditator` in either direction.
     * A shorter OCR title may confirm the longer card only when [allowTruncatedTitle]
     * is set, which is the collector-number case (`Goblin-town` + 65).
     */
    fun mtgTitlesAgree(
        cardName: String?,
        ocrName: String?,
        allowTruncatedTitle: Boolean = false,
    ): Boolean {
        val card = CardIdentity.titleWords(cardName.orEmpty())
        val query = CardIdentity.titleWords(ocrName.orEmpty())
        if (card.isEmpty() || query.isEmpty()) return false
        if (CardIdentity.alignedTitleTokens(cardName.orEmpty(), ocrName.orEmpty())) return true
        if (!allowTruncatedTitle || query.size >= card.size) return false
        return query.all { token -> card.any { other -> CardIdentity.wordClose(token, other) } }
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
     * `Goblir` / `Goblin-town` against "Down, Down to Goblin-town" when the collector matches.
     * A truncated title token matches too (`Squadr` against `Squadra`).
     * A short rules fragment (`nchar`) does not match.
     * An extra whole word (`Mirkwood` / `Mirkwood Meditator`) does not match.
     */
    fun partialMtgTitle(cardName: String?, ocrName: String?): Boolean =
        mtgTitlesAgree(cardName, ocrName, allowTruncatedTitle = false)
}
