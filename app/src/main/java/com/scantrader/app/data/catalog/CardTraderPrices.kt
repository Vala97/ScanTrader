package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.network.cardtrader.CardTraderApi
import com.scantrader.app.network.cardtrader.CardTraderCatalog
import com.scantrader.app.network.cardtrader.CtBlueprint
import com.scantrader.app.network.cardtrader.CtExpansion
import com.scantrader.app.network.cardtrader.CtProduct

/**
 * Marketplace average for one print. No token means no call: the caller keeps Scryfall.
 * Expansions and blueprints stay in memory; listings are read each time.
 */
class CardTraderPrices(
    private val api: CardTraderApi,
    private val token: () -> String?,
) {
    private var expansions: List<CtExpansion>? = null
    private val blueprints = mutableMapOf<Int, List<CtBlueprint>>()

    suspend fun quote(
        card: IdentifiedCard,
        language: CardLanguage,
        condition: CardCondition,
        foil: Boolean,
        extraScryfallIds: List<String> = emptyList(),
    ): PriceQuote? {
        if (token()?.isBlank() != false) return null
        if (card.game == Game.RIFTBOUND || (card.game != Game.MTG && card.ctBlueprintId != null)) return riftboundQuote(card, language, condition, foil)
        if (card.game != Game.MTG) return null
        return runCatching {
            val expansionId = CardTraderCatalog.expansionId(loadExpansions(), card.setCode) ?: return null
            val prints = blueprints.getOrPut(expansionId) { api.blueprints(expansionId) }
            val ids = (listOfNotNull(card.scryfallId) + extraScryfallIds).distinct()
            val blueprintId = CardTraderCatalog.blueprintId(prints, ids) ?: return null
            val products = api.products(blueprintId).values.flatten()
            ListingAverage.average(
                listings = products.map { it.toListing() },
                language = language.cardTraderLang(),
                condition = if (condition == CardCondition.DAMAGED) "Poor" else condition.csvValue,
                foil = foil,
            )
        }.onFailure {
            com.scantrader.app.scan.ScanDebug.w("CardTrader price failed", it)
        }.getOrNull()
    }

    /** Riftbound: the blueprint id comes with the catalog hit, so the listings are one call. */
    private suspend fun riftboundQuote(card: IdentifiedCard, language: CardLanguage, condition: CardCondition, foil: Boolean): PriceQuote? {
        val id = card.ctBlueprintId ?: return null
        return runCatching {
            ListingAverage.average(
                listings = api.products(id).values.flatten().map { it.toListing() },
                language = language.cardTraderLang(),
                condition = if (condition == CardCondition.DAMAGED) "Poor" else condition.csvValue,
                foil = foil,
            )
        }.onFailure { com.scantrader.app.scan.ScanDebug.w("CardTrader Riftbound price failed", it) }.getOrNull()
    }

    private suspend fun loadExpansions(): List<CtExpansion> {
        expansions?.let { return it }
        val loaded = api.expansions()
        expansions = loaded
        return loaded
    }

    private fun CtProduct.toListing(): MarketListing = MarketListing(
        cents = price?.cents ?: 0,
        currency = price?.currency.orEmpty(),
        language = properties?.mtgLanguage ?: properties?.pokemonLanguage ?: properties?.riftboundLanguage ?: properties?.otherLanguage(),
        condition = properties?.condition,
        foil = properties?.mtgFoil ?: properties?.riftboundFoil,
    )
}
