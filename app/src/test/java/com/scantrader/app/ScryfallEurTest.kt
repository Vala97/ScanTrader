package com.scantrader.app

import com.scantrader.app.data.catalog.EurPrint
import com.scantrader.app.data.catalog.ScryfallEur
import com.scantrader.app.data.catalog.ScryfallEurPrices
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.network.scryfall.ScryfallApi
import com.scantrader.app.network.scryfall.ScryfallCard
import com.scantrader.app.network.scryfall.ScryfallClient
import com.scantrader.app.network.scryfall.ScryfallGate
import com.scantrader.app.network.scryfall.ScryfallPrices
import com.scantrader.app.network.scryfall.ScryfallSearchResponse
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ScryfallEurTest {
    @Test
    fun exactEurIsScryfallNotApproximate() {
        val quote = ScryfallEur.quote(
            exact = EurPrint(eur = "0.12", eurFoil = "0.40", id = "it"),
            english = EurPrint(eur = "9.00", id = "en"),
            others = listOf(EurPrint(eur = "0.01", id = "other")),
            foil = false,
        )
        assertEquals("0.12", quote?.amount)
        assertEquals("EUR", quote?.currency)
        assertEquals("Scryfall", quote?.source)
    }

    @Test
    fun englishSameSetIsUsedWhenTheExactPrintingHasNoEur() {
        val quote = ScryfallEur.quote(
            exact = EurPrint(eur = null, id = "it"),
            english = EurPrint(eur = "0.80", id = "en"),
            others = listOf(EurPrint(eur = "0.05", releasedAt = "2024-01-01", id = "cheap")),
            foil = false,
        )
        assertEquals("0.80", quote?.amount)
        assertEquals("Scryfall (circa)", quote?.source)
    }

    @Test
    fun cheapestPrintingThenMostRecentRelease() {
        val quote = ScryfallEur.quote(
            exact = EurPrint(id = "it"),
            english = EurPrint(id = "en"),
            others = listOf(
                EurPrint(eur = "2.00", releasedAt = "2008-02-01", id = "old"),
                EurPrint(eur = "2.00", releasedAt = "2020-04-17", id = "newer"),
                EurPrint(eur = "3.50", releasedAt = "2024-01-01", id = "pricey"),
                EurPrint(eur = "0", id = "zero"),
            ),
            foil = false,
        )
        assertEquals("2.00", quote?.amount)
        assertEquals("Scryfall (circa)", quote?.source)
        val picked = ScryfallEur.cheapestPrint(
            listOf(
                EurPrint(eur = "2.00", releasedAt = "2008-02-01", id = "old"),
                EurPrint(eur = "2.00", releasedAt = "2020-04-17", id = "newer"),
                EurPrint(eur = "3.50", releasedAt = "2024-01-01", id = "pricey"),
            ),
            foil = false,
        )
        assertEquals("newer", picked?.first?.id)
    }

    @Test
    fun foilPrefersEurFoilOnTheExactPrinting() {
        val quote = ScryfallEur.quote(
            exact = EurPrint(eur = "0.12", eurFoil = "1.25", id = "it"),
            english = EurPrint(eurFoil = "0.01", id = "en"),
            others = emptyList(),
            foil = true,
        )
        assertEquals("1.25", quote?.amount)
        assertEquals("Scryfall", quote?.source)
    }

    @Test
    fun foilFallsBackToEnglishEurFoilBeforeNonfoil() {
        val quote = ScryfallEur.quote(
            exact = EurPrint(eur = "0.12", id = "it"),
            english = EurPrint(eur = "0.12", eurFoil = "4.00", id = "en"),
            others = listOf(EurPrint(eurFoil = "0.50", id = "other")),
            foil = true,
        )
        assertEquals("4.00", quote?.amount)
        assertEquals("Scryfall (circa)", quote?.source)
    }

    @Test
    fun foilWithNoEurFoilAnywhereUsesNonfoilAsApproximate() {
        val quote = ScryfallEur.quote(
            exact = EurPrint(eur = "0.12", id = "it"),
            english = EurPrint(eur = "0.30", id = "en"),
            others = listOf(EurPrint(eur = "0.01", id = "other")),
            foil = true,
        )
        assertEquals("0.12", quote?.amount)
        assertEquals("Scryfall (circa)", quote?.source)
    }

    @Test
    fun noEurAnywhereIsNull() {
        assertNull(
            ScryfallEur.quote(
                exact = EurPrint(id = "it"),
                english = EurPrint(id = "en"),
                others = listOf(EurPrint(eur = "0.00", id = "zero")),
                foil = false,
            ),
        )
    }

    @Test
    fun storedExactEurDoesNotHitTheNetwork() = runBlocking {
        val api = FakeScryfall()
        val prices = prices(api)
        val quote = prices.quote(mortal(priceEur = "0.12", priceSource = "Scryfall"), foil = false)
        assertEquals("0.12", quote?.amount)
        assertEquals("Scryfall", quote?.source)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun italianPrintingWithoutEurUsesEnglishAndSkipsTheReprintSearch() = runBlocking {
        val api = FakeScryfall()
        api.byId = printCard(id = "it-id", lang = "it", eur = null, oracleId = "oracle-1")
        api.english = printCard(id = "en-id", lang = "en", eur = "0.12", oracleId = "oracle-1")
        val quote = prices(api).quote(mortal(), foil = false)
        assertEquals("0.12", quote?.amount)
        assertEquals("Scryfall (circa)", quote?.source)
        assertEquals(listOf("id", "en"), api.calls)
    }

    @Test
    fun missingEurOnExactAndEnglishUsesTheCheapestReprint() = runBlocking {
        val api = FakeScryfall()
        api.byId = printCard(id = "it-id", lang = "it", eur = null, oracleId = "oracle-1")
        api.english = printCard(id = "en-id", lang = "en", eur = null, oracleId = "oracle-1")
        api.prints = listOf(
            printCard(id = "a", lang = "en", eur = "1.50", releasedAt = "2024-01-01", oracleId = "oracle-1"),
            printCard(id = "b", lang = "en", eur = "0.40", releasedAt = "2011-01-01", oracleId = "oracle-1"),
            printCard(id = "c", lang = "en", eur = "0.40", releasedAt = "2019-06-01", oracleId = "oracle-1"),
        )
        val quote = prices(api).quote(mortal(), foil = false)
        assertEquals("0.40", quote?.amount)
        assertEquals("Scryfall (circa)", quote?.source)
        assertEquals(listOf("id", "en", "search"), api.calls)
    }

    @Test
    fun approximateLabelIsResolvedAgainFromTheExactPrinting() = runBlocking {
        val api = FakeScryfall()
        api.byId = printCard(id = "it-id", lang = "it", eur = "0.12", oracleId = "oracle-1")
        val quote = prices(api).quote(
            mortal(priceEur = "9.99", priceSource = "Scryfall (circa)"),
            foil = false,
        )
        assertEquals("0.12", quote?.amount)
        assertEquals("Scryfall", quote?.source)
        assertEquals(listOf("id"), api.calls)
    }

    private fun prices(api: FakeScryfall) =
        ScryfallEurPrices(ScryfallClient(api, ScryfallGate(minSpacingMs = 0L, now = { 0L })))

    private fun mortal(priceEur: String? = null, priceSource: String? = null) = IdentifiedCard(
        game = Game.MTG,
        name = "Ostinazione Mortale",
        setCode = "jou",
        setName = "Journey into Nyx",
        collectorNumber = "17",
        scryfallId = "it-id",
        priceEur = priceEur,
        priceSource = priceSource,
    )

    private fun printCard(
        id: String,
        lang: String,
        eur: String?,
        oracleId: String,
        releasedAt: String = "2014-04-26",
    ) = ScryfallCard(
        id = id,
        name = "Mortal Obstinacy",
        set = "jou",
        setName = "Journey into Nyx",
        collectorNumber = "17",
        lang = lang,
        oracleId = oracleId,
        releasedAt = releasedAt,
        prices = ScryfallPrices(usd = "0.15", eur = eur),
    )

    private class FakeScryfall : ScryfallApi {
        val calls = mutableListOf<String>()
        var byId: ScryfallCard? = null
        var english: ScryfallCard? = null
        var prints: List<ScryfallCard> = emptyList()

        override suspend fun cardBySetAndNumber(set: String, number: String): ScryfallCard =
            error("unused")

        override suspend fun cardBySetNumberLang(set: String, number: String, lang: String): ScryfallCard {
            calls += "en"
            return english ?: throw notFound()
        }

        override suspend fun cardById(id: String): ScryfallCard {
            calls += "id"
            return byId ?: error("missing id")
        }

        override suspend fun search(query: String, unique: String): ScryfallSearchResponse {
            calls += "search"
            return ScryfallSearchResponse(prints)
        }

        override suspend fun namedFuzzy(fuzzy: String): ScryfallCard = error("unused")

        override suspend fun namedFuzzyInSet(fuzzy: String, set: String): ScryfallCard = error("unused")

        private fun notFound(): HttpException {
            val body = "no".toResponseBody("text/plain".toMediaType())
            return HttpException(Response.error<String>(404, body))
        }
    }
}
