package com.scantrader.app

import com.scantrader.app.data.catalog.CardLanguageResolver
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.network.scryfall.ScryfallApi
import com.scantrader.app.network.scryfall.ScryfallCard
import com.scantrader.app.network.scryfall.ScryfallClient
import com.scantrader.app.network.scryfall.ScryfallSearchResponse
import com.scantrader.app.network.tcgdex.TcgdexApi
import com.scantrader.app.network.tcgdex.TcgdexCard
import com.scantrader.app.network.tcgdex.TcgdexCardBrief
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardLanguageResolverTest {
    @Test
    fun multilingualNamesAreFetchedOncePerCardId() = runBlocking {
        var calls = 0
        val resolver = resolver { lang, _ ->
            calls++
            when (lang) {
                "it" -> "Maxus del Team Rocket"
                "en" -> "Team Rocket's Petrel"
                else -> throw IOException("missing")
            }
        }
        val card = petrel()
        val ocr = "Maxus del Team Rocket"
        val first = resolver.explain(card, ocr, ocr)
        val afterFirst = calls
        val second = resolver.explain(card, ocr, ocr)
        assertEquals(CardLanguage.ITALIAN, first.language)
        assertEquals(CardLanguage.ITALIAN, second.language)
        assertTrue(afterFirst >= 2)
        assertEquals(afterFirst, calls)
    }

    @Test
    fun aFailedLookupIsNotCachedAndDoesNotInventALanguage() = runBlocking {
        var calls = 0
        var fail = true
        val resolver = resolver { lang, _ ->
            calls++
            if (fail) throw IOException("503")
            when (lang) {
                "it" -> "Maxus del Team Rocket"
                "en" -> "Team Rocket's Petrel"
                else -> throw IOException("missing")
            }
        }
        val card = petrel()
        val failed = resolver.explain(card, "Maxus del Team Rocket", "Maxus del Team Rocket")
        assertEquals(null, failed.language)
        val failedCalls = calls
        assertTrue(failedCalls >= 10)
        val retried = resolver.explain(card, "Maxus del Team Rocket", "Maxus del Team Rocket")
        assertEquals(null, retried.language)
        assertTrue(calls > failedCalls)
        fail = false
        val recovered = resolver.explain(card, "Maxus del Team Rocket", "Maxus del Team Rocket")
        assertEquals(CardLanguage.ITALIAN, recovered.language)
    }

    private fun petrel() = IdentifiedCard(
        game = Game.POKEMON,
        name = "Team Rocket's Petrel",
        setCode = "sv10",
        setName = "Destined Rivals",
        collectorNumber = "176",
        tcgdexId = "sv10-176",
    )

    private fun resolver(names: (String, String) -> String): CardLanguageResolver {
        val tcgdex = object : TcgdexApi {
            override suspend fun search(lang: String, name: String?, localId: String?): List<TcgdexCardBrief> =
                emptyList()

            override suspend fun card(lang: String, id: String): TcgdexCard =
                TcgdexCard(id = id, localId = "176", name = names(lang, id))
        }
        val scryfall = ScryfallClient(object : ScryfallApi {
            override suspend fun cardBySetAndNumber(set: String, number: String): ScryfallCard = error("unused")
            override suspend fun cardBySetNumberLang(set: String, number: String, lang: String): ScryfallCard =
                error("unused")
            override suspend fun cardById(id: String): ScryfallCard = error("unused")
            override suspend fun search(query: String, unique: String): ScryfallSearchResponse = error("unused")
            override suspend fun namedFuzzy(fuzzy: String): ScryfallCard = error("unused")
            override suspend fun namedFuzzyInSet(fuzzy: String, set: String): ScryfallCard = error("unused")
        })
        return CardLanguageResolver(scryfall, tcgdex)
    }
}
