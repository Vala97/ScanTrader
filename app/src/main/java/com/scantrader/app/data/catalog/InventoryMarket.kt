package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard

/**
 * A fresh market quote for one saved row.
 * Pokémon is Cardmarket. One Piece and Riftbound need the CardTrader token.
 * Magic keeps its CardTrader listing, then Scryfall.
 */
object InventoryMarket {
    sealed class Result {
        data class Priced(val card: IdentifiedCard) : Result()
        data object Unavailable : Result()
        data object NeedsToken : Result()
    }

    suspend fun quote(
        card: IdentifiedCard,
        language: CardLanguage,
        condition: CardCondition,
        foil: Boolean,
        hasCardTraderToken: Boolean,
        cardTraderPrices: CardTraderPrices,
        scryfallEur: ScryfallEurPrices,
        englishScryfallId: suspend (setCode: String, collectorNumber: String) -> String?,
        pokemon: suspend (IdentifiedCard, CardLanguage) -> PriceQuote?,
        onePieceBlueprint: suspend (IdentifiedCard) -> Int?,
    ): Result = when (card.game) {
        Game.POKEMON -> {
            val market = runCatching { pokemon(card, language) }.getOrNull()
            if (market == null) {
                Result.Unavailable
            } else {
                Result.Priced(ListingAverage.apply(card.withoutPokemonFoil(), market, foil = false))
            }
        }
        Game.ONE_PIECE -> {
            if (!hasCardTraderToken) {
                Result.NeedsToken
            } else {
                val id = runCatching { onePieceBlueprint(card) }.getOrNull()
                val market = if (id == null) {
                    null
                } else {
                    runCatching {
                        cardTraderPrices.quote(
                            card.copy(ctBlueprintId = id),
                            language,
                            condition,
                            foil = false,
                        )
                    }.getOrNull()
                }
                if (market == null) {
                    Result.Unavailable
                } else {
                    Result.Priced(
                        ListingAverage.apply(
                            card.copy(priceEurFoil = null, priceUsdFoil = null),
                            market,
                            foil = false,
                        ),
                    )
                }
            }
        }
        Game.RIFTBOUND -> {
            if (!hasCardTraderToken) {
                Result.NeedsToken
            } else {
                fromExisting(card, language, condition, foil && card.game.foilChoice, true, cardTraderPrices, scryfallEur, englishScryfallId)
            }
        }
        Game.MTG -> fromExisting(
            card,
            language,
            condition,
            foil && card.game.foilChoice,
            hasCardTraderToken,
            cardTraderPrices,
            scryfallEur,
            englishScryfallId,
        )
        else -> Result.Unavailable
    }

    private suspend fun fromExisting(
        card: IdentifiedCard,
        language: CardLanguage,
        condition: CardCondition,
        foil: Boolean,
        hasCardTraderToken: Boolean,
        cardTraderPrices: CardTraderPrices,
        scryfallEur: ScryfallEurPrices,
        englishScryfallId: suspend (setCode: String, collectorNumber: String) -> String?,
    ): Result {
        val priced = MarketPrice.propose(
            card = card,
            language = language,
            condition = condition,
            foil = foil,
            hasCardTraderToken = hasCardTraderToken,
            cardTraderPrices = cardTraderPrices,
            scryfallEur = scryfallEur,
            englishScryfallId = englishScryfallId,
        )
        return if (priced.priceSource.isNullOrBlank()) Result.Unavailable else Result.Priced(priced)
    }
}
