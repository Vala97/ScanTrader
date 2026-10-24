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
        if (card.game != Game.POKEMON) return true
        return PokemonCatalog.nameMatches(card.name, frameName) ||
            PokemonCatalog.nameMatches(card.printedName, frameName)
    }
}
