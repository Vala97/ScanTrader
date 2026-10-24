package com.scantrader.app.network.scryfall

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/** One in-flight Scryfall call at a time, spaced by [ScryfallGate]. */
class ScryfallClient(
    private val api: ScryfallApi,
    val gate: ScryfallGate = ScryfallGate(),
) {
    private val mutex = Mutex()

    fun coolingDown(): Boolean = gate.coolingDown()

    suspend fun cardBySetAndNumber(set: String, number: String): ScryfallCard {
        val key = "set:${gate.normalize(set)}:${gate.normalize(number)}"
        return read(key) { api.cardBySetAndNumber(set, number) }
    }

    suspend fun cardById(id: String): ScryfallCard {
        val key = "id:${gate.normalize(id)}"
        return read(key) { api.cardById(id) }
    }

    suspend fun search(query: String, unique: String = "prints"): ScryfallSearchResponse {
        val key = "q:${gate.normalize(query)}:$unique"
        return read(key) { api.search(query, unique) }
    }

    /** Single closest card. Null when Scryfall has no fuzzy match (cached). */
    suspend fun namedFuzzy(name: String): ScryfallCard? {
        val key = "fuzzy:${gate.normalize(name)}"
        return when (val hit = gate.cached(key)) {
            is ScryfallGate.Cached.Value -> hit.payload as ScryfallCard
            ScryfallGate.Cached.Empty -> null
            null -> try {
                val card = guarded { api.namedFuzzy(name) }
                gate.store(key, ScryfallGate.Cached.Value(card))
                card
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    gate.store(key, ScryfallGate.Cached.Empty)
                    null
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun <T : Any> read(key: String, block: suspend () -> T): T {
        when (val hit = gate.cached(key)) {
            is ScryfallGate.Cached.Value -> @Suppress("UNCHECKED_CAST") return hit.payload as T
            ScryfallGate.Cached.Empty -> throw ScryfallMissException()
            null -> Unit
        }
        return try {
            val value = guarded(block)
            gate.store(key, ScryfallGate.Cached.Value(value))
            value
        } catch (e: HttpException) {
            if (e.code() == 404) {
                gate.store(key, ScryfallGate.Cached.Empty)
            }
            throw e
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T {
        if (gate.coolingDown()) throw ScryfallBusyException()
        return mutex.withLock {
            if (gate.coolingDown()) throw ScryfallBusyException()
            var attempt = 0
            while (true) {
                val wait = gate.millisUntilAllowed()
                if (wait > 0) delay(wait)
                gate.markStarted()
                try {
                    return@withLock block()
                } catch (e: HttpException) {
                    if (e.code() != 429 || attempt >= 2) {
                        if (e.code() == 429) {
                            gate.onRateLimited(e.response()?.headers()?.get("Retry-After"), attempt)
                            throw ScryfallBusyException()
                        }
                        throw e
                    }
                    val backoff = gate.onRateLimited(
                        e.response()?.headers()?.get("Retry-After"),
                        attempt,
                    )
                    attempt++
                    delay(backoff)
                }
            }
            error("unreachable")
        }
    }
}

class ScryfallBusyException : RuntimeException("Scryfall occupato, riprovo…")

class ScryfallMissException : RuntimeException()
