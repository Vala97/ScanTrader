package com.scantrader.app

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.export.BlueprintNames
import com.scantrader.app.data.model.Game
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

class BlueprintNamesTest {
    @Test
    fun italianScryfallIdBecomesEnglishBlueprintName() = runBlocking {
        val saved = mutableListOf<CardEntryEntity>()
        val names = BlueprintNames(
            ScryfallClient(scryfall(), ScryfallGate(minSpacingMs = 0L, now = { 0L })),
            emptyTcgdex(),
        ) { saved += it }
        val row = mtg(
            name = "Stendardiere del Lancio della Rana",
            scryfallId = "it-id",
        )
        val filled = names.fill(listOf(row)).single()
        assertEquals("Stendardiere del Lancio della Rana", filled.name)
        assertEquals("Frogtosser Banneret", filled.catalogName)
        assertEquals("en-id", filled.catalogScryfallId)
        assertEquals("en-id", saved.single().catalogScryfallId)
    }

    @Test
    fun englishPrintingKeepsItsScryfallId() = runBlocking {
        val saved = mutableListOf<CardEntryEntity>()
        val names = BlueprintNames(
            ScryfallClient(scryfall(), ScryfallGate(minSpacingMs = 0L, now = { 0L })),
            emptyTcgdex(),
        ) { saved += it }
        val filled = names.fill(listOf(mtg(name = "Diabolic Edict", scryfallId = "en-edict"))).single()
        assertEquals("Diabolic Edict", filled.catalogName)
        assertEquals(null, filled.catalogScryfallId)
        assertEquals("en-edict", filled.scryfallId)
        assertEquals(null, saved.single().catalogScryfallId)
    }

    @Test
    fun skipsRowsThatAlreadyHaveACatalogName() = runBlocking {
        var calls = 0
        val api = object : ScryfallApi by unusedApi() {
            override suspend fun cardById(id: String): ScryfallCard {
                calls++
                error("should not fetch")
            }
        }
        val names = BlueprintNames(
            ScryfallClient(api, ScryfallGate(minSpacingMs = 0L, now = { 0L })),
            emptyTcgdex(),
        ) { error("should not save") }
        val row = mtg(name = "Stendardiere del Lancio della Rana", scryfallId = "it-id")
            .copy(catalogName = "Frogtosser Banneret")
        val filled = names.fill(listOf(row)).single()
        assertEquals("Frogtosser Banneret", filled.catalogName)
        assertEquals(0, calls)
    }

    @Test
    fun pokemonUsesTheEnglishTcgdexName() = runBlocking {
        val tcgdex = object : TcgdexApi {
            override suspend fun search(
                lang: String,
                name: String?,
                localId: String?,
            ): List<TcgdexCardBrief> = emptyList()

            override suspend fun card(lang: String, id: String): TcgdexCard {
                assertEquals("en", lang)
                assertTrue(id == "asc-72" || id == "asc-072")
                if (id != "asc-072") throw notFound()
                return TcgdexCard(id = "asc-072", localId = "072", name = "Iono's Kilowattrel")
            }
        }
        val saved = mutableListOf<CardEntryEntity>()
        val names = BlueprintNames(
            ScryfallClient(unusedApi(), ScryfallGate(minSpacingMs = 0L, now = { 0L })),
            tcgdex,
        ) { saved += it }
        val row = CardEntryEntity(
            collectionId = 1,
            game = Game.POKEMON,
            name = "Kilowattrel di Kissara",
            setCode = "asc",
            setName = "Ascended Heroes",
            collectorNumber = "72",
            scryfallId = null,
            tcgplayerId = null,
            imageUrl = null,
            quantity = 1,
            language = "Italian",
            condition = "Near Mint",
            foil = false,
        )
        val filled = names.fill(listOf(row)).single()
        assertEquals("Kilowattrel di Kissara", filled.name)
        assertEquals("Iono's Kilowattrel", filled.catalogName)
        assertEquals(1, saved.size)
    }

    @Test
    fun offlineLookupLeavesThePrintedName() = runBlocking {
        val names = BlueprintNames(
            ScryfallClient(unusedApi(), ScryfallGate(minSpacingMs = 0L, now = { 0L })),
            emptyTcgdex(),
        ) { error("should not save") }
        val row = mtg(name = "Stendardiere del Lancio della Rana", scryfallId = "missing")
        val filled = names.fill(listOf(row)).single()
        assertEquals("Stendardiere del Lancio della Rana", filled.name)
        assertEquals(null, filled.catalogName)
    }

    private fun mtg(name: String, scryfallId: String) = CardEntryEntity(
        collectionId = 1,
        game = Game.MTG,
        name = name,
        setCode = "mor",
        setName = "Morningtide",
        collectorNumber = "64",
        scryfallId = scryfallId,
        tcgplayerId = null,
        imageUrl = null,
        quantity = 1,
        language = "Italian",
        condition = "Near Mint",
        foil = false,
    )

    private fun scryfall(): ScryfallApi = object : ScryfallApi by unusedApi() {
        override suspend fun cardById(id: String): ScryfallCard = when (id) {
            "it-id" -> ScryfallCard(
                id = "it-id",
                name = "Frogtosser Banneret",
                printedName = "Stendardiere del Lancio della Rana",
                set = "mor",
                collectorNumber = "64",
                lang = "it",
            )
            "en-edict" -> ScryfallCard(
                id = "en-edict",
                name = "Diabolic Edict",
                set = "a25",
                collectorNumber = "85",
                lang = "en",
            )
            else -> throw notFound()
        }

        override suspend fun cardBySetNumberLang(set: String, number: String, lang: String): ScryfallCard {
            if (set == "mor" && number == "64" && lang == "en") {
                return ScryfallCard(
                    id = "en-id",
                    name = "Frogtosser Banneret",
                    set = "mor",
                    collectorNumber = "64",
                    lang = "en",
                )
            }
            throw notFound()
        }
    }

    private fun unusedApi(): ScryfallApi = object : ScryfallApi {
        override suspend fun cardBySetAndNumber(set: String, number: String): ScryfallCard = error("unused")
        override suspend fun cardBySetNumberLang(set: String, number: String, lang: String): ScryfallCard =
            error("unused")
        override suspend fun cardById(id: String): ScryfallCard = throw notFound()
        override suspend fun search(query: String, unique: String): ScryfallSearchResponse = error("unused")
        override suspend fun namedFuzzy(fuzzy: String): ScryfallCard = error("unused")
        override suspend fun namedFuzzyInSet(fuzzy: String, set: String): ScryfallCard = error("unused")
    }

    private fun emptyTcgdex(): TcgdexApi = object : TcgdexApi {
        override suspend fun search(lang: String, name: String?, localId: String?): List<TcgdexCardBrief> =
            emptyList()
        override suspend fun card(lang: String, id: String): TcgdexCard = throw notFound()
    }

    private fun notFound(): HttpException {
        val body = "no".toResponseBody("text/plain".toMediaType())
        return HttpException(Response.error<String>(404, body))
    }
}
