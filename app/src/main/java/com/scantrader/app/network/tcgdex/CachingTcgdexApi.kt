package com.scantrader.app.network.tcgdex

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException

/**
 * Memory and disk cache for TCGdex card-by-id and name/localId search.
 * A 404 is remembered. Other failures are not, so a 503 can be retried.
 */
class TcgdexResponseCache(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    private val memory = ConcurrentHashMap<String, String>()

    fun get(key: String): String? {
        memory[key]?.let { return it }
        val file = fileFor(key)
        if (!file.isFile) return null
        if (now() - file.lastModified() > maxAgeMs) {
            file.delete()
            return null
        }
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        memory[key] = text
        return text
    }

    fun put(key: String, value: String) {
        memory[key] = value
        val file = fileFor(key)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(value)
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir, name)
    }

    companion object {
        const val MISS = "\u0000miss"
        const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    }
}

class CachingTcgdexApi(
    private val delegate: TcgdexApi,
    private val cache: TcgdexResponseCache,
    private val gson: Gson = Gson(),
) : TcgdexApi {
    private val briefList = object : TypeToken<List<TcgdexCardBrief>>() {}.type

    override suspend fun search(lang: String, name: String?, localId: String?): List<TcgdexCardBrief> {
        val key = "search|$lang|${name.orEmpty()}|${localId.orEmpty()}"
        cache.get(key)?.let { cached ->
            if (cached == TcgdexResponseCache.MISS) return emptyList()
            return gson.fromJson(cached, briefList)
        }
        val briefs = delegate.search(lang, name, localId)
        cache.put(key, gson.toJson(briefs))
        return briefs
    }

    override suspend fun card(lang: String, id: String): TcgdexCard {
        val key = "card|$lang|$id"
        cache.get(key)?.let { cached ->
            if (cached == TcgdexResponseCache.MISS) throw cachedMiss()
            return gson.fromJson(cached, TcgdexCard::class.java)
        }
        return try {
            val card = delegate.card(lang, id)
            cache.put(key, gson.toJson(card))
            card
        } catch (error: HttpException) {
            if (error.code() == 404) cache.put(key, TcgdexResponseCache.MISS)
            throw error
        }
    }

    private fun cachedMiss(): HttpException {
        val body = "miss".toResponseBody("text/plain".toMediaType())
        return HttpException(retrofit2.Response.error<TcgdexCard>(404, body))
    }
}
