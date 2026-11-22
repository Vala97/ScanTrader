package com.scantrader.app.data.catalog

/**
 * Small thread-safe LRU map. The set snapshots a session touches stay in
 * memory only up to [maxEntries]; older ones are read from disk again.
 */
class BoundedCache<K : Any, V : Any>(private val maxEntries: Int) {
    init {
        require(maxEntries > 0)
    }

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    /** Removes [key] only while it still maps to [value]. */
    @Synchronized
    fun remove(key: K, value: V): Boolean {
        if (map[key] != value) return false
        map.remove(key)
        return true
    }

    @Synchronized
    fun values(): List<V> = map.values.toList()

    @Synchronized
    fun keys(): List<K> = map.keys.toList()

    val size: Int
        @Synchronized get() = map.size
}
