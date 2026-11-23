package com.scantrader.app.scan

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Bitmaps one manual scan created (photo, guide crop, 180° copy, OCR scales).
 * They are freed once, together, after the scan and its image jobs finished.
 * Same object tracked twice is freed once; nothing is freed after a cancel,
 * because ML Kit may still be reading one of them.
 */
class ScanBitmapLease<T : Any>(private val free: (T) -> Unit) {
    private val owned: MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())
    private var released = false

    @Synchronized
    fun track(vararg items: T?): ScanBitmapLease<T> {
        if (released) return this
        for (item in items) if (item != null) owned += item
        return this
    }

    @Synchronized
    fun size(): Int = owned.size

    /** Frees every tracked item once. Later calls do nothing. */
    @Synchronized
    fun releaseAll(): Int {
        if (released) return 0
        released = true
        var count = 0
        for (item in owned) {
            runCatching { free(item) }
            count++
        }
        owned.clear()
        return count
    }

    /** Drops the items without freeing them (cancelled scan). */
    @Synchronized
    fun abandon() {
        released = true
        owned.clear()
    }
}

/** Message the confirm screen shows for a failed scan. */
object ScanErrors {
    const val OUT_OF_MEMORY = "Memoria esaurita durante lo scan: riprova. Se succede spesso, riavvia l'app."

    /**
     * Runs an image-only step (gallery embed). Out of memory there returns
     * [fallback] so OCR and the catalog still produce candidates.
     */
    inline fun <T> orOnOutOfMemory(fallback: T, onOom: (OutOfMemoryError) -> Unit = {}, block: () -> T): T =
        try {
            block()
        } catch (oom: OutOfMemoryError) {
            onOom(oom)
            fallback
        }

    fun message(t: Throwable): String = when {
        t is OutOfMemoryError || t.cause is OutOfMemoryError -> OUT_OF_MEMORY
        else -> t.message ?: "Errore durante lo scan"
    }
}
