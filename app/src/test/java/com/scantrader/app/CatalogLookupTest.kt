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
        val searchCalls = mutableListOf<String>()
        val tcgdex = object : TcgdexApi {
            override suspend fun search(
                lang: String,
                name: String?,
                localId: String?,
            ): List<TcgdexCardBrief> {
                searchCalls += "$lang name=${name.orEmpty()} localId=${localId.orEmpty()}"
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
        assertTrue(searchCalls.isNotEmpty())
        assertTrue(searchCalls.all { it.contains("name=Machop") && it.endsWith("localId=") })
        assertTrue(cardCalls.isNotEmpty())
        assertTrue(cardCalls.all { it.endsWith(":base1-52") || it.endsWith(":base1-052") })
        assertTrue(cardCalls.none { it.contains("wird") })
        assertEquals("Machop", hits.first().name)
        assertEquals("base1", hits.first().setCode)
        assertEquals("52", hits.first().collectorNumber)
    }

    @Test
    fun cherubiWithPrintedTotal128RanksTheNameOverOtherNumberSixes() = runBlocking {
        val cardCalls = mutableListOf<String>()
        val searchCalls = mutableListOf<String>()
        val tcgdex = object : TcgdexApi {
            override suspend fun search(
                lang: String,
                name: String?,
                localId: String?,
            ): List<TcgdexCardBrief> {
                searchCalls += "$lang name=${name.orEmpty()} localId=${localId.orEmpty()}"
                if (!localId.isNullOrBlank()) {
                    return listOf(
                        TcgdexCardBrief("pop9-6", "6", "Buizel"),
                        TcgdexCardBrief("pop7-6", "6", "Delibird"),
                        TcgdexCardBrief("pop8-6", "6", "Cherrim"),
                    )
                }
                if (name.equals("Cherubi", ignoreCase = true)) {
                    return listOf(
                        TcgdexCardBrief("30th-006", "006", "Cherubi"),
                        TcgdexCardBrief("bw8-6", "6", "Cherubi"),
                        TcgdexCardBrief("pop9-6", "6", "Buizel"),
                    )
                }
                return emptyList()
            }

            override suspend fun card(lang: String, id: String): TcgdexCard {
                cardCalls += "$lang:$id"
                return when (id) {
                    "30th-006", "30th-6" -> TcgdexCard(
                        id = "30th-006",
                        localId = "006",
                        name = "Cherubi",
                        image = "https://assets.tcgdex.net/$lang/me/30th/006",
                        set = com.scantrader.app.network.tcgdex.TcgdexSet(
                            id = "30th",
                            name = "30th Celebration",
                            cardCount = com.scantrader.app.network.tcgdex.TcgdexCardCount(official = 128),
                        ),
                    )
                    "bw8-6" -> TcgdexCard(
                        id = "bw8-6",
                        localId = "6",
                        name = "Cherubi",
                        image = "https://assets.tcgdex.net/$lang/bw/bw8/6",
                        set = com.scantrader.app.network.tcgdex.TcgdexSet(
                            id = "bw8",
                            name = "Plasma Storm",
                            cardCount = com.scantrader.app.network.tcgdex.TcgdexCardCount(official = 135),
                        ),
                    )
                    "pop9-6" -> TcgdexCard(
                        id = "pop9-6",
                        localId = "6",
                        name = "Buizel",
                        set = com.scantrader.app.network.tcgdex.TcgdexSet(id = "pop9", name = "POP Series 9"),
                    )
                    else -> throw notFound()
                }
            }
        }
        val lookup = CatalogLookup(
            ScryfallClient(unusedScryfall(), ScryfallGate(minSpacingMs = 0L, now = { 0L })),
            tcgdex,
        )
        val hits = lookup.identify(
            Game.POKEMON,
            ParsedCardText(nameGuess = "Cherubi", collectorNumber = "6", setCode = null, setCardCount = 128),
        )
        assertTrue(searchCalls.any { it.contains("name=Cherubi") && it.endsWith("localId=") })
        assertTrue(searchCalls.none { it.contains("localId=6") || it.contains("localId=006") })
        assertTrue(cardCalls.none { it.contains("pop") })
        assertEquals("Cherubi", hits.first().name)
        assertEquals("30th", hits.first().setCode)
        assertEquals("006", hits.first().collectorNumber)
        assertTrue(hits.none { it.name == "Buizel" || it.name == "Delibird" || it.name == "Cherrim" })
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
