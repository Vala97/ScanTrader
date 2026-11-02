package com.scantrader.app.scan

/**
 * Milo starts with OCR. A catalog query awaits that embed instead of waiting
 * until Scryfall returns empty. A debounce hold still does not wait for it.
 * Cooldown cannot call Scryfall, so the gallery is the lookup.
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
        return queryCatalog || cooling
    }

    fun embedAfterCatalog(
        autoMode: Boolean,
        queryCatalog: Boolean,
        hitCount: Int,
        ranAlongside: Boolean = false,
    ): Boolean = autoMode && queryCatalog && hitCount <= 0 && !ranAlongside
}
