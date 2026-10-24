package com.scantrader.app

import com.scantrader.app.data.catalog.ListingAverage
import com.scantrader.app.data.catalog.MarketListing
import com.scantrader.app.network.cardtrader.CardTraderCatalog
import com.scantrader.app.network.cardtrader.CtBlueprint
import com.scantrader.app.network.cardtrader.CtExpansion
import com.google.gson.Gson
import com.scantrader.app.network.scryfall.ScryfallCard
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
    fun scryfallFallsBackToUsdWhenEurIsMissing() {
        assertEquals("EUR", ListingAverage.fromScryfall("0.12", null, "0.15", null, foil = false)?.currency)
        val usd = ListingAverage.fromScryfall(null, null, "0.15", "0.40", foil = true)
        assertEquals("0.40", usd?.amount)
        assertEquals("USD", usd?.currency)
        assertEquals("Scryfall", usd?.source)
        assertNull(ListingAverage.fromScryfall(null, null, null, null, foil = false))
    }

    @Test
    fun labelIncludesTheSource() {
        assertEquals(
            "€0.12 · Scryfall",
            ListingAverage.display("0.12", null, "0.15", null, "EUR", "Scryfall", foil = false),
        )
        assertEquals(
            "$0.15 · Scryfall",
            ListingAverage.display(null, null, "0.15", null, "USD", "Scryfall", foil = false),
        )
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
              "prices": {"usd": "0.15", "usd_foil": "0.40", "eur": "0.12", "eur_foil": null}
            }
            """.trimIndent(),
            ScryfallCard::class.java,
        )
        assertEquals("0.12", card.prices?.eur)
        assertEquals("0.15", card.prices?.usd)
        assertEquals("0.40", card.prices?.usdFoil)
        assertNull(card.prices?.eurFoil)
    }
}
