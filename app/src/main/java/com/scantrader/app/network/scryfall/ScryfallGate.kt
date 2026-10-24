package com.scantrader.app.network.scryfall

import java.util.Locale

/**
 * Scryfall asks for at most about 10 requests/second. This gate spaces calls,
 * remembers query results (including misses) and holds a cooldown after HTTP 429.
 */
class ScryfallGate(
    private val minSpacingMs: Long = 110L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var nextAtMs: Long = 0L
    private var cooldownUntilMs: Long = 0L
    private val cache = LinkedHashMap<String, Cached>()

    fun normalize(query: String): String =
        query.trim().lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")

    fun coolingDown(): Boolean = now() < cooldownUntilMs

    /** Wait before the next HTTP call. Zero when a request may start now. */
    @Synchronized
    fun millisUntilAllowed(): Long {
        val t = now()
        return maxOf(nextAtMs - t, 0L)
    }

    @Synchronized
    fun markStarted() {
        val t = now()
        nextAtMs = maxOf(nextAtMs, t) + minSpacingMs
    }

    /**
     * @param retryAfter Scryfall `Retry-After` header, in seconds when it is an integer.
     * @return how long the caller should wait before another attempt.
     */
    @Synchronized
    fun onRateLimited(retryAfter: String?, attempt: Int): Long {
        val headerMs = retryAfter?.trim()?.toLongOrNull()?.times(1_000L)
        val exponential = 500L shl attempt.coerceIn(0, 4)
        val wait = (headerMs ?: exponential).coerceIn(200L, 30_000L)
        val until = now() + wait
        cooldownUntilMs = maxOf(cooldownUntilMs, until)
        nextAtMs = maxOf(nextAtMs, until)
        return wait
    }

    @Synchronized
    fun cached(key: String): Cached? = cache[key]

    @Synchronized
    fun store(key: String, value: Cached) {
        cache[key] = value
        while (cache.size > 80) {
            val eldest = cache.entries.firstOrNull()?.key ?: break
            cache.remove(eldest)
        }
    }

    sealed class Cached {
        data class Value(val payload: Any) : Cached()
        data object Empty : Cached()
    }
}
