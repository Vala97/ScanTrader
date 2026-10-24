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
        return agreesName(card.game, card.name, card.printedName, frameName)
    }

    /** Fuzzy title check. An id hit whose name disagrees is not a match. */
    fun agreesName(game: Game, cardName: String?, printedName: String?, ocrName: String): Boolean {
        if (game == Game.POKEMON) {
            return PokemonCatalog.nameMatches(cardName, ocrName) ||
                PokemonCatalog.nameMatches(printedName, ocrName)
        }
        return CardIdentity.namesSimilar(cardName, ocrName) ||
            CardIdentity.namesSimilar(printedName, ocrName) ||
            partialMtgTitle(cardName, ocrName) ||
            partialMtgTitle(printedName, ocrName)
    }

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
