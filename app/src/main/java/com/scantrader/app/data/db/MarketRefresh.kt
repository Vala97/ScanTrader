package com.scantrader.app.data.db

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard

/**
 * One market quote shared by every row of the same print.
 * Game, language, condition, foil, and variant stay apart, so a foil price
 * cannot land on the normal copy and Magic cannot land on Pokémon.
 */
object MarketRefresh {
    data class Decision(
        val entry: CardEntryEntity,
        /** Write [entry] back. Manual price is never part of this change. */
        val save: Boolean,
        /** A market amount was found. False keeps the previous automatic price. */
        val priced: Boolean,
    )

    fun canQuote(game: Game): Boolean =
        game == Game.MTG || game == Game.RIFTBOUND || game == Game.POKEMON || game == Game.ONE_PIECE

    fun quoteKey(entry: CardEntryEntity): String {
        val foil = entry.foil && entry.game.foilChoice
        return listOf(
            entry.game.name,
            entry.setCode.trim().lowercase(),
            entry.collectorNumber.trim().lowercase(),
            entry.scryfallId.orEmpty().trim(),
            entry.catalogScryfallId.orEmpty().trim(),
            entry.tcgdexId.orEmpty().trim(),
            entry.language.trim().lowercase(),
            entry.condition.trim().lowercase(),
            if (foil) "foil" else "normal",
            entry.variant.orEmpty().trim().lowercase(),
            versionToken(entry.name),
        ).joinToString("|")
    }

    /** Parenthetical finish on a saved title (`DON!! (Gold)`). Empty when the print has none. */
    fun versionToken(name: String): String =
        Regex("""\(([^)]+)\)\s*$""").find(name)?.groupValues?.get(1)?.trim()?.lowercase().orEmpty()

    /**
     * Copy a market result onto [entry]. [priced] must be the quote alone:
     * empty prices and a null source mean the fetch failed.
     * A manual amount on [entry] is left as the effective price.
     */
    fun applyQuote(entry: CardEntryEntity, priced: IdentifiedCard): Decision {
        if (priced.priceSource.isNullOrBlank()) {
            return Decision(entry, save = false, priced = false)
        }
        val foil = entry.foil && entry.game.foilChoice
        val updated = entry.copy(
            priceEur = if (foil) entry.priceEur else priced.priceEur ?: entry.priceEur,
            priceEurFoil = when {
                !entry.game.foilChoice -> null
                foil -> priced.priceEurFoil ?: entry.priceEurFoil
                else -> entry.priceEurFoil
            },
            priceCurrency = priced.priceCurrency ?: entry.priceCurrency,
            priceSource = priced.priceSource,
        )
        if (CardPrice.automaticRaw(updated) == null) {
            return Decision(entry, save = false, priced = false)
        }
        val changed = updated.priceEur != entry.priceEur ||
            updated.priceEurFoil != entry.priceEurFoil ||
            updated.priceCurrency != entry.priceCurrency ||
            updated.priceSource != entry.priceSource
        return Decision(entry = if (changed) updated else entry, save = changed, priced = true)
    }
}
