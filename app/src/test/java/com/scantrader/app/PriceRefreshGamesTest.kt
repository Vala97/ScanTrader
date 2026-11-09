package com.scantrader.app

import com.scantrader.app.data.catalog.CardTraderPrices
import com.scantrader.app.data.catalog.CtBlueprintMatch
import com.scantrader.app.data.catalog.PokemonMarket
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.network.cardtrader.CardTraderApi
import com.scantrader.app.network.cardtrader.CtBlueprint
import com.scantrader.app.network.cardtrader.CtExpansion
import com.scantrader.app.network.cardtrader.CtMoney
import com.scantrader.app.network.cardtrader.CtProduct
import com.scantrader.app.network.cardtrader.CtProperties
import com.scantrader.app.network.tcgdex.TcgdexCard
import com.scantrader.app.network.tcgdex.TcgdexCardmarket
import com.scantrader.app.network.tcgdex.TcgdexPricing
import com.scantrader.app.network.tcgdex.TcgdexSet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceRefreshGamesTest {
    @Test
    fun pokemonUsesCardmarketAverageAndIgnoresHolo() {
        val card = tcg("sv3-1", "sv3", "1", avg = 1.2, avgHolo = 50.0, trend = 0.4)
        assertEquals("1.20", PokemonMarket.price(card)?.amount)
        assertEquals("Cardmarket", PokemonMarket.price(card)?.source)
        assertEquals(
            "0.50",
            PokemonMarket.price(tcg("sv3-1", "sv3", "1", avg = null, trend = 0.5, avgHolo = 9.0))?.amount,
        )
        assertNull(PokemonMarket.price(tcg("sv3-1", "sv3", "1", avg = null, trend = null, avgHolo = 9.0)))
        assertNull(PokemonMarket.price(tcg("sv3-1", "sv3", "1", avg = 0.0, trend = null, avgHolo = 3.0)))
    }

    @Test
    fun pokemonEnglishFallbackIsTheSameIdAndJapaneseStaysJapanese() = runBlocking {
        val calls = mutableListOf<String>()
        val quote = PokemonMarket.quote("sv3-1", "sv3", "1", CardLanguage.ITALIAN) { lang, id ->
            calls += "$lang|$id"
            if (lang == "en") tcg(id, "sv3", "1", avg = 1.5) else tcg(id, "sv3", "1", avg = null, trend = null)
        }
        assertEquals("1.50", quote?.amount)
        assertEquals(listOf("it|sv3-1", "en|sv3-1"), calls)

        val japanese = mutableListOf<String>()
        val missing = PokemonMarket.quote("sv2a-1", "sv2a", "1", CardLanguage.JAPANESE) { lang, id ->
            japanese += lang
            tcg(id, "sv2a", "1", avg = null, trend = null)
        }
        assertNull(missing)
        assertEquals(listOf("ja"), japanese)
    }

    @Test
    fun pokemonWithoutAnIdUsesTheSingleMatchingPrint() = runBlocking {
        val quote = PokemonMarket.quote(null, "base1", "4", CardLanguage.ENGLISH) { _, id ->
            if (id == "base1-004") error(id)
            tcg("base1-4", "base1", "4", avg = 2.0)
        }
        assertEquals("2.00", quote?.amount)
    }

    @Test
    fun pokemonRejectsAnotherSetAndAnAmbiguousNumber() = runBlocking {
        val wrongSet = PokemonMarket.quote("base1-4", "base1", "4", CardLanguage.ENGLISH) { _, id ->
            tcg(id, "sv3", "4", avg = 12.0)
        }
        assertNull(wrongSet)

        val ambiguous = PokemonMarket.quote(null, "sv3", "1", CardLanguage.ENGLISH) { _, id ->
            tcg(id, "sv3", "1", avg = 1.0)
        }
        assertNull(ambiguous)
    }

    @Test
    fun onePiecePrintIsExactForAlternateArtAndDon() {
        val expansions = listOf(CtExpansion(5, 15, "OP-02", "OP-02"), CtExpansion(8, 15, "EB-03", "EB-03"))
        val prints = mapOf(
            5 to listOf(
                bp(10, "OP02-013", null),
                bp(11, "OP02-013a", "Alternate Art"),
            ),
            8 to listOf(
                bp(20, "EB-03-NAMI", null),
                bp(21, "EB-03-NAMI-G", "Gold"),
                bp(22, "EB-03-ROBIN", null),
            ),
        )
        fun id(set: String, number: String, name: String) = runBlocking {
            CtBlueprintMatch.uniqueId(set, number, name, expansions) { prints[it.id].orEmpty() }
        }

        assertEquals(10, id("op02", "OP02-013", "Whitebeard"))
        assertEquals(11, id("op-02", "OP02-013a", "Whitebeard (Alternate Art)"))
        assertEquals(20, id("eb03", "EB-03-NAMI", "DON!!"))
        assertEquals(21, id("eb03", "EB-03-NAMI-G", "DON!! (Gold)"))
        assertNull(id("op03", "OP02-013", "Whitebeard"))
    }

    @Test
    fun twoVersionsWithoutAUniqueNameAreNotPriced() {
        val expansions = listOf(CtExpansion(5, 15, "op02", "OP-02"))
        val prints = listOf(bp(10, "OP02-013", "Manga"), bp(11, "OP02-013", "Wanted"))
        assertNull(runBlocking {
            CtBlueprintMatch.uniqueId("op02", "OP02-013", "Ace", expansions) { prints }
        })
        assertEquals(11, runBlocking {
            CtBlueprintMatch.uniqueId("op02", "OP02-013", "Ace (Wanted)", expansions) { prints }
        })
    }

    @Test
    fun onePieceQuoteUsesOnlyThatBlueprint() = runBlocking {
        val asked = mutableListOf<Pair<Int, Boolean?>>()
        val api = object : CardTraderApi {
            override suspend fun expansions() = listOf(
                CtExpansion(99, 1, "op02", "Magic"),
                CtExpansion(5, 15, "op02", "OP-02"),
                CtExpansion(6, 15, "op03", "OP-03"),
            )

            override suspend fun blueprints(expansionId: Int) = when (expansionId) {
                5 -> listOf(bp(10, "OP02-013", null), bp(11, "OP02-013a", "Alternate Art"))
                6 -> listOf(bp(77, "OP02-013", null))
                else -> emptyList()
            }

            override suspend fun products(blueprintId: Int, language: String?, foil: Boolean?): Map<String, List<CtProduct>> {
                asked += blueprintId to foil
                val cents = when (blueprintId) {
                    10 -> 150
                    11 -> 900
                    else -> 1
                }
                return mapOf("1" to listOf(product(cents, language ?: "en")))
            }
        }
        val prices = CardTraderPrices(api) { "token" }
        val saved = IdentifiedCard(
            game = Game.ONE_PIECE,
            name = "Whitebeard",
            setCode = "op02",
            setName = "OP-02",
            collectorNumber = "OP02-013",
        )
        val id = prices.onePieceBlueprintId(saved)
        assertEquals(10, id)
        val quote = prices.quote(
            saved.copy(ctBlueprintId = id),
            CardLanguage.ITALIAN,
            CardCondition.NEAR_MINT,
            foil = false,
        )
        assertEquals("1.50", quote?.amount)
        assertTrue(asked.all { it.first == 10 && it.second == null })
        val calls = asked.size
        assertNull(CardTraderPrices(api) { null }.onePieceBlueprintId(saved))
        assertEquals(calls, asked.size)
    }

    private fun tcg(
        id: String,
        set: String,
        local: String,
        avg: Double? = null,
        trend: Double? = null,
        avgHolo: Double? = null,
    ) = TcgdexCard(
        id = id,
        localId = local,
        name = "Pikachu",
        set = TcgdexSet(id = set, name = set),
        pricing = TcgdexPricing(TcgdexCardmarket(avg = avg, avgHolo = avgHolo, trend = trend)),
    )

    private fun bp(id: Int, number: String, version: String?) = CtBlueprint(
        id = id,
        name = "Card",
        version = version,
        fixedProperties = mapOf("collector_number" to number),
    )

    private fun product(cents: Int, language: String) = CtProduct(
        price = CtMoney(cents = cents, currency = "EUR"),
        quantity = 1,
        properties = CtProperties(condition = "Near Mint", onepieceLanguage = language),
    )
}
