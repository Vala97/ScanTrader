package com.scantrader.app

import com.scantrader.app.data.catalog.CardTraderPrices
import com.scantrader.app.data.catalog.ListingAverage
import com.scantrader.app.data.catalog.MarketListing
import com.scantrader.app.data.catalog.PriceQuote
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.network.cardtrader.CardTraderApi
import com.scantrader.app.network.cardtrader.CardTraderCatalog
import com.scantrader.app.network.cardtrader.CtBlueprint
import com.scantrader.app.network.cardtrader.CtExpansion
import com.scantrader.app.network.cardtrader.CtProduct
import com.google.gson.Gson
import com.scantrader.app.network.scryfall.ScryfallCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListingAverageTest {
    @Test
    fun averagesMatchingLanguageConditionAndFoil() {
        val listings = listOf(
            MarketListing(100, "EUR", "it", "Near Mint", false),
            MarketListing(300, "EUR", "it", "Near Mint", false),
            MarketListing(900, "EUR", "en", "Near Mint", false),
            MarketListing(50, "EUR", "it", "Near Mint", true),
        )
        val quote = ListingAverage.average(listings, language = "it", condition = "Near Mint", foil = false)
        assertEquals("2.00", quote?.amount)
        assertEquals("EUR", quote?.currency)
        assertEquals("CardTrader", quote?.source)
    }

    @Test
    fun usesEveryListingWhenTheFilterMatchesNothing() {
        val listings = listOf(
            MarketListing(100, "EUR", "en", "Near Mint", false),
            MarketListing(300, "EUR", "fr", "Played", false),
        )
        val quote = ListingAverage.average(listings, language = "it", condition = "Near Mint", foil = false)
        assertEquals("2.00", quote?.amount)
    }

    @Test
    fun emptyListHasNoAverage() {
        assertNull(ListingAverage.average(emptyList(), "it", "Near Mint", false))
    }

    @Test
    fun labelIncludesTheEuroSourceAndSkipsUsd() {
        assertEquals(
            "€0.12 · Scryfall",
            ListingAverage.display("0.12", null, "Scryfall", foil = false),
        )
        assertEquals(
            "€0.40 · Scryfall (circa)",
            ListingAverage.display("0.12", "0.40", "Scryfall (circa)", foil = true),
        )
        assertNull(ListingAverage.display(null, null, "Scryfall", foil = false))
        assertEquals(
            "€1.50 · Cardmarket",
            ListingAverage.display("1.5", null, "Cardmarket", foil = false),
        )
    }

    @Test
    fun usdQuoteIsNotApplied() {
        val card = IdentifiedCard(
            game = Game.MTG,
            name = "Mortal Obstinacy",
            setCode = "jou",
            setName = "Journey into Nyx",
            collectorNumber = "17",
        )
        val same = ListingAverage.apply(card, PriceQuote("0.15", "USD", "Scryfall"), foil = false)
        assertNull(same.priceEur)
        assertNull(same.priceCurrency)
        assertNull(same.priceSource)
    }

    @Test
    fun cardTraderQuoteDoesNotCallTheApiWithoutAToken() = runBlocking {
        var calls = 0
        val api = object : CardTraderApi {
            override suspend fun expansions(): List<CtExpansion> {
                calls++
                error("no token")
            }

            override suspend fun blueprints(expansionId: Int): List<CtBlueprint> {
                calls++
                error("no token")
            }

            override suspend fun products(blueprintId: Int): Map<String, List<CtProduct>> {
                calls++
                error("no token")
            }
        }
        val card = IdentifiedCard(
            game = Game.MTG,
            name = "Mortal Obstinacy",
            setCode = "jou",
            setName = "Journey into Nyx",
            collectorNumber = "17",
            scryfallId = "it-id",
        )
        assertNull(
            CardTraderPrices(api) { null }.quote(card, CardLanguage.ITALIAN, CardCondition.NEAR_MINT, false),
        )
        assertNull(
            CardTraderPrices(api) { "  " }.quote(card, CardLanguage.ITALIAN, CardCondition.NEAR_MINT, false),
        )
        assertEquals(0, calls)
    }

    @Test
    fun blueprintMatchesScryfallIdInsideTheExpansion() {
        val expansions = listOf(
            CtExpansion(id = 8, gameId = 5, code = "jou", name = "Other game"),
            CtExpansion(id = 74, gameId = 1, code = "jou", name = "Journey into Nyx"),
        )
        assertEquals(74, CardTraderCatalog.expansionId(expansions, "JOU"))
        val blueprints = listOf(
            CtBlueprint(id = 10, scryfallId = "other"),
            CtBlueprint(id = 17, scryfallId = "8370b216-90ab-44d1-8147-ba160483b1a2"),
        )
        assertEquals(
            17,
            CardTraderCatalog.blueprintId(blueprints, listOf("8370B216-90AB-44D1-8147-BA160483B1A2")),
        )
        assertNull(CardTraderCatalog.blueprintId(blueprints, listOf("missing")))
    }

    @Test
    fun scryfallCardJsonKeepsEurAndUsd() {
        val card = Gson().fromJson(
            """
            {
              "id": "8370b216-90ab-44d1-8147-ba160483b1a2",
              "name": "Mortal Obstinacy",
              "set": "jou",
              "set_name": "Journey into Nyx",
              "collector_number": "17",
              "lang": "en",
              "oracle_id": "oracle-mortal",
              "released_at": "2014-04-26",
              "prices": {"usd": "0.15", "usd_foil": "0.40", "eur": "0.12", "eur_foil": null}
            }
            """.trimIndent(),
            ScryfallCard::class.java,
        )
        assertEquals("0.12", card.prices?.eur)
        assertEquals("en", card.lang)
        assertEquals("oracle-mortal", card.oracleId)
        assertEquals("2014-04-26", card.releasedAt)
        assertEquals("0.15", card.prices?.usd)
        assertEquals("0.40", card.prices?.usdFoil)
        assertNull(card.prices?.eurFoil)
    }
}
