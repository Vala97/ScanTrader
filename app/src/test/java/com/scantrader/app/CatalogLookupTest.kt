package com.scantrader.app

import com.scantrader.app.data.catalog.CatalogLookup
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.network.scryfall.ScryfallApi
import com.scantrader.app.network.scryfall.ScryfallCard
import com.scantrader.app.network.scryfall.ScryfallClient
import com.scantrader.app.network.scryfall.ScryfallGate
import com.scantrader.app.network.scryfall.ScryfallSearchResponse
import com.scantrader.app.network.tcgdex.TcgdexApi
import com.scantrader.app.network.tcgdex.TcgdexCard
import com.scantrader.app.network.tcgdex.TcgdexCardBrief
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class CatalogLookupTest {
    @Test
    fun italianPrintedNameResolvesThenCollector64IsMorningtide() = runBlocking {
        val queries = mutableListOf<String>()
        var fuzzyCalls = 0
        val italian = ScryfallCard(
            id = "znc-it",
            name = "Frogtosser Banneret",
            printedName = "Stendardiere del Lancio della Rana",
            set = "znc",
            setName = "Zendikar Rising Commander",
            collectorNumber = "43",
        )
        val morningtide = ScryfallCard(
            id = "mor-64",
            name = "Frogtosser Banneret",
            set = "mor",
            setName = "Morningtide",
            collectorNumber = "64",
        )
        val api = object : ScryfallApi {
            override suspend fun cardBySetAndNumber(set: String, number: String): ScryfallCard =
                error("unused")

            override suspend fun cardBySetNumberLang(
                set: String,
                number: String,
                lang: String,
            ): ScryfallCard = error("unused")

            override suspend fun cardById(id: String): ScryfallCard = error("unused")

            override suspend fun search(query: String, unique: String): ScryfallSearchResponse {
                queries += query
                val q = query.lowercase()
                if (q.contains("cn:64") && q.contains("!\"frogtosser banneret\"")) {
                    return ScryfallSearchResponse(listOf(morningtide))
                }
                // lang:it !"…" is the printed_name search. The English name: field misses.
                if (q.contains("lang:it") && q.contains("!\"stendardiere del lancio della rana\"")) {
                    return ScryfallSearchResponse(listOf(italian))
                }
                throw notFound()
            }

            override suspend fun namedFuzzy(fuzzy: String): ScryfallCard {
                fuzzyCalls++
                throw notFound()
            }
        }
        val lookup = CatalogLookup(
            ScryfallClient(api, ScryfallGate(minSpacingMs = 0L, now = { 0L })),
            object : TcgdexApi {
                override suspend fun search(
                    lang: String,
                    name: String?,
                    localId: String?,
                ): List<TcgdexCardBrief> = emptyList()

                override suspend fun card(lang: String, id: String): TcgdexCard = error("unused")
            },
        )
        val parsed = ParsedCardText(
            nameGuess = "Stendardiere del Lancio della Rana 1W",
            collectorNumber = "64",
            setCode = null,
        )
        val hits = lookup.identify(Game.MTG, parsed)
        assertEquals(1, hits.size)
        assertEquals("64", hits[0].collectorNumber)
        assertEquals("mor", hits[0].setCode)
        assertEquals("Morningtide", hits[0].setName)
        assertEquals("Stendardiere del Lancio della Rana", hits[0].name)
        assertTrue(fuzzyCalls >= 1)
        assertTrue(queries.any { it.contains("lang:it") && it.contains("!\"") })
        val printedAt = queries.indexOfFirst { it.contains("lang:it") && it.contains("!\"Stendardiere") }
        val morningtideAt = queries.indexOfFirst { it.contains("cn:64") && it.contains("Frogtosser Banneret") }
        assertTrue(printedAt >= 0 && morningtideAt > printedAt)
    }

    @Test
    fun unknownPrintedSetUsesTheCardCountAsADirectId() = runBlocking {
        val cardCalls = mutableListOf<String>()
        var searches = 0
        val tcgdex = object : TcgdexApi {
            override suspend fun search(
                lang: String,
                name: String?,
                localId: String?,
            ): List<TcgdexCardBrief> {
                searches++
                return emptyList()
            }

            override suspend fun card(lang: String, id: String): TcgdexCard {
                cardCalls += "$lang:$id"
                if (id == "base1-52") {
                    return TcgdexCard(
                        id = "base1-52",
                        localId = "52",
                        name = "Machop",
                        image = "https://assets.tcgdex.net/$lang/base1/52",
                        set = com.scantrader.app.network.tcgdex.TcgdexSet(
                            id = "base1",
                            name = "Base Set",
                            cardCount = com.scantrader.app.network.tcgdex.TcgdexCardCount(official = 102),
                        ),
                    )
                }
                throw notFound()
            }
        }
        val lookup = CatalogLookup(
            ScryfallClient(unusedScryfall(), ScryfallGate(minSpacingMs = 0L, now = { 0L })),
            tcgdex,
        )
        val hits = lookup.identify(
            Game.POKEMON,
            ParsedCardText(nameGuess = "Machop", collectorNumber = "52", setCode = "wird", setCardCount = 102),
        )
        assertEquals(0, searches)
        assertTrue(cardCalls.isNotEmpty())
        assertTrue(cardCalls.all { it.endsWith(":base1-52") || it.endsWith(":base1-052") })
        assertTrue(cardCalls.none { it.contains("wird") })
        assertEquals("base1", hits.first().setCode)
        assertEquals("52", hits.first().collectorNumber)
    }

    private fun unusedScryfall(): ScryfallApi = object : ScryfallApi {
        override suspend fun cardBySetAndNumber(set: String, number: String): ScryfallCard = error("unused")
        override suspend fun cardBySetNumberLang(set: String, number: String, lang: String): ScryfallCard =
            error("unused")
        override suspend fun cardById(id: String): ScryfallCard = error("unused")
        override suspend fun search(query: String, unique: String): ScryfallSearchResponse = error("unused")
        override suspend fun namedFuzzy(fuzzy: String): ScryfallCard = error("unused")
    }

    private fun notFound(): HttpException {
        val body = "no".toResponseBody("text/plain".toMediaType())
        return HttpException(Response.error<String>(404, body))
    }
}
