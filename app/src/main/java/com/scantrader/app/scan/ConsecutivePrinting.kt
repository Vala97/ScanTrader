package com.scantrader.app.scan

import com.scantrader.app.data.catalog.CardIdentity
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard

/**
 * One physical printing: game, set, collector number, language, and finish.
 * A different language or a different variant is a different card.
 * Missing language does not match anything, so an unresolved hit is never blocked.
 */
data class PrintingIdentity(
    val game: Game,
    val setCode: String,
    val collectorNumber: String,
    val language: String,
    val variant: String,
) {
    companion object {
        fun from(card: IdentifiedCard): PrintingIdentity? {
            val set = card.setCode.trim().lowercase().takeIf { it.isNotBlank() } ?: return null
            val cn = CardIdentity.normCollector(card.collectorNumber) ?: return null
            // The title printed on the card wins over the catalog object's language.
            // Morningtide's Italian copy is still the English Scryfall printing.
            val language = card.printedLang?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: card.catalogLang?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: return null
            val variant = card.finish?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "standard"
            return PrintingIdentity(card.game, set, cn, language, variant)
        }
    }
}

/**
 * Continuous scan accepts a printing once. The same printing cannot be the
 * next accept; a different language, finish, set, or collector number can.
 */
class ConsecutivePrintingGuard {
    private var last: PrintingIdentity? = null

    fun sameAsLast(card: IdentifiedCard): Boolean {
        val id = PrintingIdentity.from(card) ?: return false
        return id == last
    }

    fun noteAccepted(card: IdentifiedCard) {
        PrintingIdentity.from(card)?.let { last = it }
    }

    fun clear() {
        last = null
    }
}
