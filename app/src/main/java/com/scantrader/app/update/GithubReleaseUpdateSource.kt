package com.scantrader.app.update

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Reads the fixed `app-update` release. A network or parse failure does not throw. */
class GithubReleaseUpdateSource(
    private val client: OkHttpClient,
    private val indexUrl: String = AppUpdateIndex.INDEX_URL,
) : UpdateSource {
    override val githubSelfUpdate: Boolean = true

    override suspend fun check(): UpdateLookup = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(indexUrl).header("Accept", "application/json").build()
        val body = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext UpdateLookup.Unavailable
                response.body?.string()
            }
        } catch (_: IOException) {
            return@withContext UpdateLookup.Unavailable
        }
        if (body.isNullOrBlank()) return@withContext UpdateLookup.Unavailable
        val update = AppUpdateIndex.parse(body) ?: return@withContext UpdateLookup.Empty
        UpdateLookup.Ready(update)
    }
}
