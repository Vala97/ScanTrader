package com.scantrader.app

import com.scantrader.app.data.catalog.CollectionPriceRefresh
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CardPrice
import com.scantrader.app.data.db.MarketRefresh
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketRefreshTest {
    @Test
    fun quoteKeySeparatesGameFoilAndVariant() {
        val normal = row(game = Game.MTG, foil = false, variant = null, condition = "Near Mint")
        val foil = normal.copy(foil = true)
        val etched = normal.copy(variant = "etched")
        val played = normal.copy(condition = "Played")
        val pokemon = normal.copy(game = Game.POKEMON, foil = true)
        assertTrue(MarketRefresh.quoteKey(normal) != MarketRefresh.quoteKey(foil))
        assertTrue(MarketRefresh.quoteKey(normal) != MarketRefresh.quoteKey(etched))
        assertTrue(MarketRefresh.quoteKey(normal) != MarketRefresh.quoteKey(played))
        assertTrue(MarketRefresh.quoteKey(normal) != MarketRefresh.quoteKey(pokemon))
        assertEquals(MarketRefresh.quoteKey(normal), MarketRefresh.quoteKey(normal.copy(id = 9L, quantity = 4)))
    }

    @Test
    fun failedQuoteKeepsTheOldAutomaticPrice() {
        val entry = row(priceEur = "1.20", priceSource = "Scryfall", manualPriceEur = null)
        val decision = MarketRefresh.applyQuote(entry, quote(eur = null, source = null))
        assertFalse(decision.save)
        assertFalse(decision.priced)
        assertEquals("1.20", decision.entry.priceEur)
        assertEquals("Scryfall", decision.entry.priceSource)
    }

    @Test
    fun foilQuoteDoesNotClearTheNonFoilAmount() {
        val entry = row(
            foil = true,
            priceEur = "1.00",
            priceSource = "Scryfall",
        ).copy(priceEurFoil = "2.00")
        val decision = MarketRefresh.applyQuote(
            entry,
            quote(eur = null, source = "Scryfall").copy(priceEurFoil = "3.00"),
        )
        assertTrue(decision.save)
        assertEquals("1.00", decision.entry.priceEur)
        assertEquals("3.00", decision.entry.priceEurFoil)
        assertEquals("3.00", CardPrice.effectiveRaw(decision.entry))
    }

    @Test
    fun freshQuoteUpdatesAutomaticAndLeavesManualEffective() {
        val entry = row(priceEur = "1.20", priceSource = "Scryfall", manualPriceEur = "9.00")
        val decision = MarketRefresh.applyQuote(entry, quote(eur = "1.50", source = "Scryfall"))
        assertTrue(decision.save)
        assertTrue(decision.priced)
        assertEquals("1.50", decision.entry.priceEur)
        assertEquals("9.00", decision.entry.manualPriceEur)
        assertEquals("9.00", CardPrice.effectiveRaw(decision.entry))
    }

    @Test
    fun oneFetchCoversDuplicatePrintsAndSkipsOtherGames() = runBlocking {
        val first = row(id = 1L, priceEur = "1.00", priceSource = "Scryfall")
        val twin = first.copy(id = 2L, quantity = 3)
        val pokemon = row(id = 3L, game = Game.POKEMON, priceEur = "0.40", priceSource = "Cardmarket")
        val seen = mutableListOf<Long>()
        val saved = mutableListOf<Long>()
        val outcome = CollectionPriceRefresh.run(
            cards = listOf(first, twin, pokemon),
            quote = { card ->
                seen += card.id
                quote(eur = "2.00", source = "Scryfall")
            },
            save = { saved += it.id },
        )
        assertEquals(listOf(1L), seen)
        assertEquals(listOf(1L, 2L), saved)
        assertEquals(2, outcome.stored)
        assertEquals(0, outcome.failed)
        assertEquals(3, outcome.total)
        assertEquals("Prezzi aggiornati", outcome.message)
    }

    @Test
    fun aFailedPrintDoesNotClearItsPrice() = runBlocking {
        val card = row(id = 4L, priceEur = "3.00", priceSource = "CardTrader")
        val saved = mutableListOf<CardEntryEntity>()
        val outcome = CollectionPriceRefresh.run(
            cards = listOf(card),
            quote = { throw IllegalStateException("offline") },
            save = { saved += it },
        )
        assertTrue(saved.isEmpty())
        assertEquals(1, outcome.failed)
        assertEquals(0, outcome.stored)
        assertEquals("Nessun prezzo di mercato trovato. I prezzi precedenti restano.", outcome.message)
    }

    private fun quote(eur: String?, source: String?) = IdentifiedCard(
        game = Game.MTG,
        name = "Bolt",
        setCode = "lea",
        setName = "Limited Edition Alpha",
        collectorNumber = "161",
        priceEur = eur,
        priceSource = source,
        priceCurrency = if (source == null) null else "EUR",
    )

    private fun row(
        id: Long = 1L,
        game: Game = Game.MTG,
        foil: Boolean = false,
        variant: String? = null,
        condition: String = "Near Mint",
        priceEur: String? = null,
        priceSource: String? = null,
        manualPriceEur: String? = null,
    ) = CardEntryEntity(
        id = id,
        collectionId = 7L,
        game = game,
        name = "Lightning Bolt",
        setCode = "lea",
        setName = "Limited Edition Alpha",
        collectorNumber = "161",
        scryfallId = "bolt",
        tcgplayerId = null,
        imageUrl = null,
        quantity = 1,
        language = "English",
        condition = condition,
        foil = foil,
        priceEur = priceEur,
        priceSource = priceSource,
        variant = variant,
        manualPriceEur = manualPriceEur,
    )
}
