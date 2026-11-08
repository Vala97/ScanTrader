package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard

/**
 * Live market EUR for one copy. Pokémon keeps its Cardmarket stamp.
 * Magic uses the cheapest CardTrader listing when a token is saved; otherwise Scryfall EUR.
 */
object MarketPrice {
    suspend fun propose(
        card: IdentifiedCard,
        language: CardLanguage,
        condition: CardCondition,
        foil: Boolean,
        hasCardTraderToken: Boolean,
        cardTraderPrices: CardTraderPrices,
        scryfallEur: ScryfallEurPrices,
        englishScryfallId: suspend (setCode: String, collectorNumber: String) -> String?,
    ): IdentifiedCard {
        if (card.game == Game.RIFTBOUND || card.ctBlueprintId != null) {
            val market = runCatching {
                cardTraderPrices.quote(card, language, condition, foil)
            }.getOrNull()
            return if (market != null) {
                ListingAverage.apply(card, market, foil)
            } else {
                ListingAverage.unavailable(card)
            }
        }
        if (card.game != Game.MTG) return card
        if (hasCardTraderToken) {
            val englishId = if (card.setCode.isNotBlank() && card.collectorNumber.isNotBlank()) {
                runCatching { englishScryfallId(card.setCode, card.collectorNumber) }.getOrNull()
            } else {
                null
            }
            val market = runCatching {
                cardTraderPrices.quote(
                    card,
                    language,
                    condition,
                    foil,
                    listOfNotNull(englishId),
                )
            }.getOrNull()
            if (market != null) {
                return ListingAverage.apply(card, market, foil)
            }
        }
        val quote = runCatching { scryfallEur.quote(card, foil) }.getOrNull()
        return if (quote == null) {
            card
        } else {
            ListingAverage.apply(card, quote, foil)
        }
    }
}
