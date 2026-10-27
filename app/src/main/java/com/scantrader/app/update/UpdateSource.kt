package com.scantrader.app.update

/**
 * Where a newer APK comes from. A Play build can supply another implementation
 * and leave [githubSelfUpdate] false so the GitHub check stays hidden.
 */
interface UpdateSource {
    val githubSelfUpdate: Boolean

    suspend fun check(): UpdateLookup
}

sealed interface UpdateLookup {
    data class Ready(val update: AppUpdate) : UpdateLookup
    data object Empty : UpdateLookup
    data object Unavailable : UpdateLookup
}

/** Play builds, or any build with GitHub self-update turned off. */
object DisabledUpdateSource : UpdateSource {
    override val githubSelfUpdate: Boolean = false

    override suspend fun check(): UpdateLookup = UpdateLookup.Empty
}
