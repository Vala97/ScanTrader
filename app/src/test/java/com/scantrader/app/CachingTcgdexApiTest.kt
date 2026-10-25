package com.scantrader.app

import com.scantrader.app.network.tcgdex.CachingTcgdexApi
import com.scantrader.app.network.tcgdex.TcgdexApi
import com.scantrader.app.network.tcgdex.TcgdexCard
import com.scantrader.app.network.tcgdex.TcgdexCardBrief
import com.scantrader.app.network.tcgdex.TcgdexResponseCache
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class CachingTcgdexApiTest {
    @Test
    fun searchAndCardAreServedFromMemory() = runBlocking {
        var searches = 0
        var cards = 0
        val api = CachingTcgdexApi(
            delegate(searches = { searches++ }, cards = { cards++ }),
            TcgdexResponseCache(tempDir()),
        )
        val first = api.search("en", "Machop", null)
        val second = api.search("en", "Machop", null)
        assertEquals("Machop", first.single().name)
        assertEquals(first.single().id, second.single().id)
        assertEquals(1, searches)
        val card = api.card("en", "base1-52")
        val again = api.card("en", "base1-52")
        assertEquals(card.id, again.id)
        assertEquals(1, cards)
    }

    @Test
    fun aMissingCardIsCachedAndAServerErrorIsNot() = runBlocking {
        var missing = 0
        val missingApi = CachingTcgdexApi(
            object : TcgdexApi {
                override suspend fun search(lang: String, name: String?, localId: String?) =
                    emptyList<TcgdexCardBrief>()

                override suspend fun card(lang: String, id: String): TcgdexCard {
                    missing++
                    throw http(404)
                }
            },
            TcgdexResponseCache(tempDir()),
        )
        runCatching { missingApi.card("en", "wird-52") }
        runCatching { missingApi.card("en", "wird-52") }
        assertEquals(1, missing)

        var serverErrors = 0
        val serverApi = CachingTcgdexApi(
            object : TcgdexApi {
                override suspend fun search(lang: String, name: String?, localId: String?) =
                    emptyList<TcgdexCardBrief>()

                override suspend fun card(lang: String, id: String): TcgdexCard {
                    serverErrors++
                    throw http(503)
                }
            },
            TcgdexResponseCache(tempDir()),
        )
        runCatching { serverApi.card("en", "base1-52") }
        runCatching { serverApi.card("en", "base1-52") }
        assertEquals(2, serverErrors)
    }

    @Test
    fun diskCacheSurvivesANewMemoryCache() = runBlocking {
        val dir = tempDir()
        var cards = 0
        val first = CachingTcgdexApi(
            delegate(searches = {}, cards = { cards++ }),
            TcgdexResponseCache(dir),
        )
        assertEquals("base1-52", first.card("it", "base1-52").id)
        val second = CachingTcgdexApi(
            object : TcgdexApi {
                override suspend fun search(lang: String, name: String?, localId: String?) = error("unused")
                override suspend fun card(lang: String, id: String): TcgdexCard = error("disk should hit")
            },
            TcgdexResponseCache(dir),
        )
        assertEquals("Machop", second.card("it", "base1-52").name)
        assertEquals(1, cards)
        assertTrue(dir.listFiles()?.isNotEmpty() == true)
    }

    private fun tempDir(): File = File.createTempFile("tcgdex", "").apply {
        delete()
        mkdirs()
    }

    private fun delegate(searches: () -> Unit, cards: () -> Unit): TcgdexApi = object : TcgdexApi {
        override suspend fun search(lang: String, name: String?, localId: String?): List<TcgdexCardBrief> {
            searches()
            return listOf(TcgdexCardBrief(id = "base1-52", localId = "52", name = name ?: "Machop"))
        }

        override suspend fun card(lang: String, id: String): TcgdexCard {
            cards()
            return TcgdexCard(id = id, localId = "52", name = "Machop")
        }
    }

    private fun http(code: Int): HttpException {
        val body = "no".toResponseBody("text/plain".toMediaType())
        return HttpException(Response.error<String>(code, body))
    }
}
