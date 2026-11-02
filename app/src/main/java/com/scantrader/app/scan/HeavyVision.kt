package com.scantrader.app.scan

/**
 * Auto-scan embeds only when the catalog cannot answer.
 * A debounce hold returns without Milo so the next frame can query.
 * Cooldown cannot call Scryfall, so the gallery runs instead.
 * A query that comes back empty embeds afterwards; a hit does not.
 * Manual scan still embeds beside the catalog.
 */
object HeavyVision {
    fun embedAlongsideCatalog(
        autoMode: Boolean,
        cooling: Boolean,
        queryCatalog: Boolean,
        stabilizeByGallery: Boolean = false,
    ): Boolean {
        if (stabilizeByGallery) return true
        if (!autoMode) return true
        return cooling && !queryCatalog
    }

    fun embedAfterCatalog(
        autoMode: Boolean,
        queryCatalog: Boolean,
        hitCount: Int,
    ): Boolean = autoMode && queryCatalog && hitCount <= 0
}
