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
 * Cheapest CardTrader listing for one language and condition. No token means no call.
 * Expansions and blueprints stay in memory; listings are read each time.
 */
class CardTraderPrices(
    private val api: CardTraderApi,
    private val expansionResolver: CtExpansionResolver? = null,
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
        if (card.game == Game.RIFTBOUND || (card.game != Game.MTG && card.ctBlueprintId != null)) {
            val id = card.ctBlueprintId ?: return null
            return quoteListings(id, card.game, language, condition, foil, "CardTrader price failed")
        }
        if (card.game != Game.MTG) return null
        return runCatching {
            val ids = (listOfNotNull(card.scryfallId) + extraScryfallIds).distinct()
            val blueprintId = findMtgBlueprintId(card.setCode, ids) ?: return null
            quoteListings(blueprintId, card.game, language, condition, foil, "CardTrader price failed")
        }.onFailure {
            com.scantrader.app.scan.ScanDebug.w("CardTrader price failed", it)
        }.getOrNull()
    }

    private suspend fun findMtgBlueprintId(setCode: String, scryfallIds: List<String>): Int? {
        val candidates = expansionResolver?.mtgExpansionCandidates(setCode).orEmpty()
        val expansionIds = if (candidates.isNotEmpty()) {
            candidates.map { it.id }
        } else {
            val loaded = loadExpansions()
            val baseId = CardTraderCatalog.expansionId(loaded, setCode) ?: return null
            val collectorCode = CtExpansionResolver.collectorSiblingCode(setCode)
            val collectorId = CardTraderCatalog.expansionId(loaded, collectorCode)
            listOfNotNull(baseId, collectorId)
        }
        for (expansionId in expansionIds) {
            val prints = blueprints.getOrPut(expansionId) { api.blueprints(expansionId) }
            val hit = CardTraderCatalog.blueprintId(prints, scryfallIds)
            if (hit != null) return hit
        }
        return null
    }

    /**
     * Asks CardTrader for that language. Foil is sent only when the game has a foil
     * property; otherwise `foil=false` comes back empty and the price disappears.
     */
    private suspend fun quoteListings(
        blueprintId: Int,
        game: Game,
        language: CardLanguage,
        condition: CardCondition,
        foil: Boolean,
        failure: String,
    ): PriceQuote? = runCatching {
        val lang = language.cardTraderLang()
        val conditionName = if (condition == CardCondition.DAMAGED) "Poor" else condition.csvValue
        val forLanguage = ListingAverage.best(
            listings = loadRows(blueprintId, game, lang, foil).map { it.toListing() },
            language = lang,
            condition = conditionName,
            foil = foil,
        )
        if (forLanguage != null) return@runCatching forLanguage
        // No copy in that language: the base price is the cheapest listing of the card.
        ListingAverage.best(
            listings = loadRows(blueprintId, game, null, foil).map { it.toListing() },
            language = null,
            condition = conditionName,
            foil = foil,
        )
    }.onFailure { com.scantrader.app.scan.ScanDebug.w(failure, it) }.getOrNull()

    private suspend fun loadRows(blueprintId: Int, game: Game, language: String?, foil: Boolean): List<CtProduct> {
        if (!game.foilChoice) return api.products(blueprintId, language).values.flatten()
        val filtered = api.products(blueprintId, language, foil).values.flatten()
        if (filtered.isNotEmpty()) return filtered
        return api.products(blueprintId, language).values.flatten()
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
        graded = graded,
        signed = properties?.signed == true,
        altered = properties?.altered == true,
        onVacation = onVacation,
        quantity = quantity,
    )
}
