package com.scantrader.app.data.catalog

/**
 * Autoscan should not hit Scryfall on every frame.
 * A lookup runs after the same normalized id (name + collector + set) has been
 * seen [stableFramesNeeded] times in a row, or once that id has stayed for
 * [requeryAfterMs]. The same id is queried again after [requeryAfterMs] so a
 * missed frame cannot block the catalog forever.
 */
class CatalogQueryDebounce(
    private val stableFramesNeeded: Int = 2,
    private val requeryAfterMs: Long = 2_000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var lastSeen: String? = null
    private var seenCount: Int = 0
    private var firstSeenAtMs: Long = 0L
    private var lastQueried: String? = null
    private var lastQueryAtMs: Long = 0L

    fun normalize(identity: String): String =
        identity.trim().lowercase().replace(Regex("""\s+"""), " ")

    /**
     * True when the previous frames already settled on this id.
     * Does not count the current frame. Used to skip extra OCR passes.
     */
    fun seenThisBefore(identity: String): Boolean {
        val key = normalize(identity)
        return key.isNotBlank() && key == lastSeen && seenCount >= 1
    }

    fun shouldQuery(identity: String): Boolean {
        val key = normalize(identity)
        val t = now()
        if (key == lastSeen) {
            seenCount++
        } else {
            lastSeen = key
            seenCount = 1
            firstSeenAtMs = t
        }
        val stable = seenCount >= stableFramesNeeded
        val waited = t - firstSeenAtMs >= requeryAfterMs
        if (!stable && !waited) return false
        if (key == lastQueried && t - lastQueryAtMs < requeryAfterMs) return false
        lastQueried = key
        lastQueryAtMs = t
        return true
    }

    /** The last identity failed (429 / cooldown). The next stable frame may query again. */
    fun invalidateLastQuery() {
        lastQueried = null
        lastQueryAtMs = 0L
    }
}
