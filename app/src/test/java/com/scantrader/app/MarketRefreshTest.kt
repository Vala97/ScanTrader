package com.scantrader.app

import com.scantrader.app.data.catalog.CollectionPriceRefresh
import com.scantrader.app.data.catalog.InventoryMarket
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
        val gold = normal.copy(game = Game.ONE_PIECE, name = "DON!! (Gold)", collectorNumber = "EB-03-NAMI-G")
        val nami = gold.copy(name = "DON!! (Nami)", collectorNumber = "EB-03-NAMI")
        assertTrue(MarketRefresh.quoteKey(normal) != MarketRefresh.quoteKey(foil))
        assertTrue(MarketRefresh.quoteKey(normal) != MarketRefresh.quoteKey(etched))
        assertTrue(MarketRefresh.quoteKey(normal) != MarketRefresh.quoteKey(played))
        assertTrue(MarketRefresh.quoteKey(normal) != MarketRefresh.quoteKey(pokemon))
        assertTrue(MarketRefresh.quoteKey(gold) != MarketRefresh.quoteKey(nami))
        assertEquals(MarketRefresh.quoteKey(normal), MarketRefresh.quoteKey(normal.copy(id = 9L, quantity = 4)))
        assertTrue(MarketRefresh.canQuote(Game.POKEMON))
        assertTrue(MarketRefresh.canQuote(Game.ONE_PIECE))
        assertFalse(MarketRefresh.canQuote(Game.YUGIOH))
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
        val yugioh = row(id = 3L, game = Game.YUGIOH, priceEur = "0.40", priceSource = "CardTrader")
        val seen = mutableListOf<Long>()
        val saved = mutableListOf<Long>()
        val outcome = CollectionPriceRefresh.run(
            cards = listOf(first, twin, yugioh),
            quote = { card ->
                seen += card.id
                InventoryMarket.Result.Priced(quote(eur = "2.00", source = "Scryfall"))
            },
            save = { saved += it.id },
        )
        assertEquals(listOf(1L), seen)
        assertEquals(listOf(1L, 2L), saved)
        assertEquals(2, outcome.stored)
        assertEquals(0, outcome.failed)
        assertEquals(1, outcome.unsupported)
        assertEquals(3, outcome.total)
        assertEquals("Prezzi aggiornati. 1 senza listino.", outcome.message)
    }

    @Test
    fun pokemonAndOnePieceRefreshTheWholeCollection() = runBlocking {
        val pokemon = row(
            id = 10L,
            game = Game.POKEMON,
            priceEur = "0.40",
            priceSource = "Cardmarket",
            manualPriceEur = "8.00",
        ).copy(tcgdexId = "sv3-1", name = "Pikachu", collectorNumber = "1", setCode = "sv3")
        val hiddenTwin = pokemon.copy(id = 11L, quantity = 2)
        val don = row(
            id = 12L,
            game = Game.ONE_PIECE,
            priceEur = "1.00",
            priceSource = "CardTrader",
        ).copy(name = "DON!! (Nami)", setCode = "eb03", collectorNumber = "EB-03-NAMI")
        val gold = don.copy(id = 13L, name = "DON!! (Gold)", collectorNumber = "EB-03-NAMI-G", priceEur = "4.00")
        val seen = mutableListOf<String>()
        val saved = mutableListOf<CardEntryEntity>()
        val outcome = CollectionPriceRefresh.run(
            cards = listOf(pokemon, hiddenTwin, don, gold),
            quote = { card ->
                seen += card.collectorNumber
                when (card.game) {
                    Game.POKEMON -> InventoryMarket.Result.Priced(
                        quote(eur = "0.90", source = "Cardmarket").copy(game = Game.POKEMON, priceEurFoil = "9.99"),
                    )
                    Game.ONE_PIECE -> InventoryMarket.Result.Priced(
                        quote(eur = "2.50", source = "CardTrader").copy(
                            game = Game.ONE_PIECE,
                            collectorNumber = card.collectorNumber,
                        ),
                    )
                    else -> InventoryMarket.Result.Unavailable
                }
            },
            save = { saved += it },
        )
        assertEquals(listOf("1", "EB-03-NAMI", "EB-03-NAMI-G"), seen)
        assertEquals(4, saved.size)
        val pricedPokemon = saved.first { it.id == 10L }
        assertEquals("0.90", pricedPokemon.priceEur)
        assertEquals(null, pricedPokemon.priceEurFoil)
        assertEquals("8.00", pricedPokemon.manualPriceEur)
        assertEquals("8.00", CardPrice.effectiveRaw(pricedPokemon))
        assertEquals("2.50", saved.first { it.id == 12L }.priceEur)
        assertEquals("2.50", saved.first { it.id == 13L }.priceEur)
        assertEquals(null, saved.first { it.id == 12L }.priceEurFoil)
        assertEquals(4, outcome.stored)
        assertEquals(0, outcome.failed)
        assertEquals("Prezzi aggiornati", outcome.message)
    }

    @Test
    fun missingTokenDoesNotReplaceOnePiecePrices() = runBlocking {
        val card = row(id = 20L, game = Game.ONE_PIECE, priceEur = "3.00", priceSource = "CardTrader")
        val saved = mutableListOf<CardEntryEntity>()
        val outcome = CollectionPriceRefresh.run(
            cards = listOf(card),
            quote = { InventoryMarket.Result.NeedsToken },
            save = { saved += it },
        )
        assertTrue(saved.isEmpty())
        assertEquals("3.00", card.priceEur)
        assertTrue(outcome.needsToken)
        assertEquals(1, outcome.failed)
        assertEquals("Serve il token CardTrader in Impostazioni. I prezzi precedenti restano.", outcome.message)
    }

    @Test
    fun pokemonUpdatesWhileOnePieceWaitsForTheToken() = runBlocking {
        val pokemon = row(id = 30L, game = Game.POKEMON, priceEur = "0.20", priceSource = "Cardmarket")
        val piece = row(id = 31L, game = Game.ONE_PIECE, priceEur = "1.00", priceSource = "CardTrader")
        val saved = mutableListOf<Long>()
        val outcome = CollectionPriceRefresh.run(
            cards = listOf(pokemon, piece),
            quote = { card ->
                if (card.game == Game.POKEMON) {
                    InventoryMarket.Result.Priced(quote(eur = "0.30", source = "Cardmarket").copy(game = Game.POKEMON))
                } else {
                    InventoryMarket.Result.NeedsToken
                }
            },
            save = { saved += it.id },
        )
        assertEquals(listOf(30L), saved)
        assertEquals(1, outcome.stored)
        assertEquals(1, outcome.failed)
        assertTrue(outcome.needsToken)
        assertEquals(
            "Aggiornati 1. One Piece e Riftbound richiedono il token CardTrader.",
            outcome.message,
        )
    }

    @Test
    fun aMissingPokemonQuoteKeepsTheOldPrice() = runBlocking {
        val card = row(id = 21L, game = Game.POKEMON, priceEur = "0.40", priceSource = "Cardmarket")
        val saved = mutableListOf<CardEntryEntity>()
        val outcome = CollectionPriceRefresh.run(
            cards = listOf(card),
            quote = { InventoryMarket.Result.Unavailable },
            save = { saved += it },
        )
        assertTrue(saved.isEmpty())
        assertEquals(1, outcome.failed)
        assertEquals(0, outcome.stored)
        assertEquals("Nessun prezzo di mercato trovato. I prezzi precedenti restano.", outcome.message)
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
