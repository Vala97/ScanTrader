package com.scantrader.app.data.catalog

import kotlinx.coroutines.withTimeoutOrNull

/** Catalog network calls give up and the caller keeps the local hit. */
object CatalogNetwork {
    const val TIMEOUT_MS = 4_000L

    /** Exact `/cards/{set}/{cn}` after the broad lookup already timed out. */
    const val EXACT_TIMEOUT_MS = 7_000L

    suspend fun <T> awaitOrNull(timeoutMs: Long = TIMEOUT_MS, fetch: suspend () -> T): T? =
        withTimeoutOrNull(timeoutMs) { fetch() }
}
