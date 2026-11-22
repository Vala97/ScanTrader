package com.scantrader.app

import com.scantrader.app.data.catalog.BoundedCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedCacheTest {
    @Test
    fun evictsTheLeastRecentlyUsedSet() {
        val cache = BoundedCache<String, Int>(3)
        cache["a"] = 1
        cache["b"] = 2
        cache["c"] = 3
        assertEquals(1, cache["a"]) // a is now the newest
        cache["d"] = 4
        assertNull(cache["b"])
        assertEquals(listOf("c", "a", "d"), cache.keys())
        assertEquals(3, cache.size)
    }

    @Test
    fun removeOnlyWhenTheValueStillMatches() {
        val cache = BoundedCache<String, String>(2)
        cache["fra"] = "old"
        cache["fra"] = "new"
        assertFalse(cache.remove("fra", "old"))
        assertEquals("new", cache["fra"])
        assertTrue(cache.remove("fra", "new"))
        assertNull(cache["fra"])
        assertTrue(cache.values().isEmpty())
    }
}
