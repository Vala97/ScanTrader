package com.scantrader.app

import com.scantrader.app.network.scryfall.ScryfallApi
import com.scantrader.app.network.scryfall.ScryfallBusyException
import com.scantrader.app.network.scryfall.ScryfallCard
import com.scantrader.app.network.scryfall.ScryfallClient
import com.scantrader.app.network.scryfall.ScryfallGate
import com.scantrader.app.network.scryfall.ScryfallMissException
import com.scantrader.app.network.scryfall.ScryfallSearchResponse
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ScryfallGateTest {
    @Test
    fun spacesCallsByAtLeastTheMinimumGap() {
        var clock = 0L
        val gate = ScryfallGate(minSpacingMs = 110L, now = { clock })
        repeat(10) {
            clock += gate.millisUntilAllowed()
            gate.markStarted()
        }
        // 10 starts with 110 ms after each one → 990 ms of scheduled gap.
        assertTrue(clock >= 900L)
    }

    @Test
    fun retryAfterOpensACooldown() {
        var clock = 1_000L
        val gate = ScryfallGate(now = { clock })
        val wait = gate.onRateLimited("2", attempt = 0)
        assertEquals(2_000L, wait)
        assertTrue(gate.coolingDown())
        clock += 2_000L
        assertTrue(!gate.coolingDown())
    }

    @Test
    fun negativeSearchIsCached() = runBlocking {
        var calls = 0
        val api = object : ScryfallApi {
            override suspend fun cardBySetAndNumber(set: String, number: String): ScryfallCard =
                error("unused")

            override suspend fun cardById(id: String): ScryfallCard = error("unused")

            override suspend fun search(query: String, unique: String): ScryfallSearchResponse {
                calls++
                throw notFound()
            }

            override suspend fun namedFuzzy(fuzzy: String): ScryfallCard = error("unused")
        }
        val client = ScryfallClient(api, ScryfallGate(minSpacingMs = 0L, now = { 0L }))
        val first = runCatching { client.search("lang:it !\"Pioppo\"") }
        val second = runCatching { client.search("lang:it !\"Pioppo\"") }
        assertTrue(first.exceptionOrNull() is HttpException)
        assertTrue(second.exceptionOrNull() is ScryfallMissException)
        assertEquals(1, calls)
    }

    @Test
    fun cooldownSkipsTheNetwork() = runBlocking {
        var calls = 0
        val gate = ScryfallGate(now = { 0L })
        gate.onRateLimited("2", attempt = 0)
        val api = object : ScryfallApi {
            override suspend fun cardBySetAndNumber(set: String, number: String): ScryfallCard =
                error("unused")

            override suspend fun cardById(id: String): ScryfallCard = error("unused")

            override suspend fun search(query: String, unique: String): ScryfallSearchResponse {
                calls++
                error("should not be called")
            }

            override suspend fun namedFuzzy(fuzzy: String): ScryfallCard = error("unused")
        }
        val client = ScryfallClient(api, gate)
        val result = runCatching { client.search("cn:83") }
        assertTrue(result.exceptionOrNull() is ScryfallBusyException)
        assertEquals(0, calls)
    }

    private fun notFound(): HttpException {
        val body = "no".toResponseBody("text/plain".toMediaType())
        return HttpException(Response.error<String>(404, body))
    }
}
