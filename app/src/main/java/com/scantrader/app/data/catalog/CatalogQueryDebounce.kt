package com.scantrader.app.data.catalog

/**
 * Autoscan should not hit Scryfall on every frame.
 * A query runs only after the same OCR identity has been seen [stableFramesNeeded]
 * times, and not again until that identity changes.
 */
class CatalogQueryDebounce(
    private val stableFramesNeeded: Int = 2,
) {
    private var lastSeen: String? = null
    private var seenCount: Int = 0
    private var lastQueried: String? = null

    fun shouldQuery(identity: String): Boolean {
        val key = identity.trim()
        if (key == lastSeen) {
            seenCount++
        } else {
            lastSeen = key
            seenCount = 1
        }
        if (seenCount < stableFramesNeeded) return false
        if (key == lastQueried) return false
        lastQueried = key
        return true
    }

    /** The last identity failed (429 / cooldown). The next stable frame may query again. */
    fun invalidateLastQuery() {
        lastQueried = null
    }
}
