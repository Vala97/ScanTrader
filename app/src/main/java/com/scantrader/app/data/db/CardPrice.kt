package com.scantrader.app.data.db

import com.scantrader.app.data.model.Game
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The price shown and exported for one stock row.
 * A manual amount replaces the automatic one. Pokémon never uses a foil price.
 */
object CardPrice {
    /** Amount shown on the card. A foil Magic card without a foil price uses the normal EUR. */
    fun automaticRaw(card: CardEntryEntity): String? {
        if (card.game != Game.POKEMON && card.foil) {
            card.priceEurFoil?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return card.priceEur?.takeIf { it.isNotBlank() }
    }

    /**
     * Amount written to CardTrader. Foil Magic uses only the foil EUR, so a missing
     * foil price stays empty. Pokémon never uses a foil amount.
     */
    fun exportRaw(card: CardEntryEntity): String? {
        card.manualPriceEur?.takeIf { it.isNotBlank() }?.let { return it }
        if (card.game == Game.POKEMON || !card.foil) return card.priceEur
        return card.priceEurFoil
    }

    fun effectiveRaw(card: CardEntryEntity): String? =
        card.manualPriceEur?.takeIf { it.isNotBlank() } ?: automaticRaw(card)

    /** Positive euro amount with two decimals, or null when the text is not a price. */
    fun parseManual(raw: String): String? {
        val parsed = raw.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
        if (parsed <= BigDecimal.ZERO) return null
        return parsed.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }
}
