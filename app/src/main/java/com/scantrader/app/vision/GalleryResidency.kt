package com.scantrader.app.vision

import com.scantrader.app.data.model.Game
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Rules for keeping one visual gallery in memory at a time. Magic alone is
 * ~68 MB of Java heap, so a second gallery next to it risks running out.
 */
object GalleryResidency {
    /** How long a scan waits for a gallery that another load is holding. */
    const val SCAN_WAIT_MS = 2_000L

    private val VISUAL = setOf(
        Game.MTG,
        Game.POKEMON,
        Game.ONE_PIECE,
        Game.DRAGON_BALL,
        Game.YUGIOH,
        Game.UNION_ARENA,
    )

    /** Galleries to drop before [keep]'s is loaded. */
    fun toDrop(keep: Game, loaded: Collection<Game>): List<Game> =
        loaded.filter { it != keep }.distinct()

    /** A changed catalog is reloaded only if its game is the one in use. */
    fun reloadAfterChange(changed: Game, active: Game?): Boolean = changed == active

    /** Game warmed at startup from the saved name; Magic when missing or unknown. */
    fun warmGame(saved: String?): Game {
        val name = saved?.trim().orEmpty()
        val game = Game.entries.firstOrNull { it.name == name }
        return if (game != null && game in VISUAL) game else Game.MTG
    }

    /**
     * Runs [block] holding [mutex] if it can be taken within [waitMs], else
     * returns null. The timeout covers only the wait: once taken, [block]
     * runs to the end.
     */
    suspend fun <T> lockedWithin(mutex: Mutex, waitMs: Long, block: suspend () -> T?): T? {
        var locked = false
        withTimeoutOrNull(waitMs) {
            mutex.lock()
            locked = true
        }
        // Checked by flag: a timeout landing right after lock() must not leak it.
        if (!locked) return null
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
