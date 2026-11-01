package com.scantrader.app.network

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Retries HTTP 429 and 503 a bounded number of times.
 * `Retry-After` wins when it is a delay in seconds or an HTTP date; otherwise
 * the wait doubles from 500 ms and stays within 200 ms and 30 s, the same
 * window as [com.scantrader.app.network.scryfall.ScryfallGate].
 *
 * When every attempt still fails, the last response is returned. Scryfall then
 * records its cooldown, and [com.scantrader.app.data.catalog.CatalogQueryDebounce]
 * drops that query so the next camera frame does not repeat it immediately.
 */
class StatusRetryInterceptor(
    private val maxAttempts: Int = DEFAULT_ATTEMPTS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = System::currentTimeMillis,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        while (true) {
            val response = chain.proceed(chain.request())
            if (!shouldRetry(response, attempt)) return response
            val wait = StatusRetry.delayMs(response.header("Retry-After"), attempt, now())
            response.close()
            sleep(wait)
            attempt++
        }
    }

    private fun shouldRetry(response: Response, attempt: Int): Boolean {
        if (response.code != 429 && response.code != 503) return false
        if (attempt >= maxAttempts - 1) return false
        val body = response.request.body
        return body == null || !body.isOneShot()
    }

    companion object {
        const val DEFAULT_ATTEMPTS = 3
    }
}

object StatusRetry {
    fun delayMs(retryAfter: String?, attempt: Int, nowMs: Long): Long {
        val header = retryAfterMs(retryAfter, nowMs)
        val exponential = 500L shl attempt.coerceIn(0, 4)
        return (header ?: exponential).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
    }

    private fun retryAfterMs(header: String?, nowMs: Long): Long? {
        val text = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { seconds ->
            if (seconds < 0L) return null
            return seconds * 1_000L
        }
        val instant = runCatching {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return (instant - nowMs).coerceAtLeast(0L)
    }

    private const val MIN_WAIT_MS = 200L
    private const val MAX_WAIT_MS = 30_000L
}
