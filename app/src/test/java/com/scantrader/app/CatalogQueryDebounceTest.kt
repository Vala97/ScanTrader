package com.scantrader.app

import com.scantrader.app.data.catalog.CatalogQueryDebounce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogQueryDebounceTest {
    @Test
    fun queriesOnceAfterTwoStableFramesAndAgainWhenIdentityChanges() {
        val debounce = CatalogQueryDebounce(stableFramesNeeded = 2)
        assertTrue(!debounce.shouldQuery("pioppo|83|"))
        assertTrue(debounce.shouldQuery("pioppo|83|"))
        assertTrue(!debounce.shouldQuery("pioppo|83|"))
        assertTrue(!debounce.shouldQuery("pioppo|83|dft"))
        assertTrue(debounce.shouldQuery("pioppo|83|dft"))
    }

    @Test
    fun failedQueryCanRunAgainForTheSameIdentity() {
        val debounce = CatalogQueryDebounce(stableFramesNeeded = 2)
        assertTrue(!debounce.shouldQuery("pioppo|83|"))
        assertTrue(debounce.shouldQuery("pioppo|83|"))
        debounce.invalidateLastQuery()
        assertTrue(debounce.shouldQuery("pioppo|83|"))
    }

    @Test
    fun sameNormalizedIdCountsAsStable() {
        val debounce = CatalogQueryDebounce(stableFramesNeeded = 2)
        assertEquals("bolla protettiva|80|", debounce.normalize("  Bolla   Protettiva|80| "))
        assertTrue(!debounce.shouldQuery("Bolla Protettiva|80|"))
        assertTrue(debounce.shouldQuery("  bolla   protettiva|80| "))
    }

    @Test
    fun seenThisBeforeLooksAtPreviousFramesOnly() {
        val debounce = CatalogQueryDebounce(stableFramesNeeded = 2)
        assertTrue(!debounce.seenThisBefore("bolla protettiva|80|"))
        assertTrue(!debounce.shouldQuery("bolla protettiva|80|"))
        assertTrue(debounce.seenThisBefore("bolla protettiva|80|"))
        assertTrue(debounce.seenThisBefore("  Bolla   Protettiva|80| "))
        assertTrue(!debounce.seenThisBefore("bolla protettiva||"))
        assertTrue(!debounce.seenThisBefore(""))
    }

    @Test
    fun timeoutQueriesBeforeTheFrameCountAndAgainAfterTheWait() {
        var t = 0L
        val debounce = CatalogQueryDebounce(
            stableFramesNeeded = 8,
            requeryAfterMs = 2_000L,
            now = { t },
        )
        assertTrue(!debounce.shouldQuery("bolla protettiva|80|"))
        t = 1_999L
        assertTrue(!debounce.shouldQuery("bolla protettiva|80|"))
        t = 2_000L
        assertTrue(debounce.shouldQuery("bolla protettiva|80|"))
        t = 2_100L
        assertTrue(!debounce.shouldQuery("bolla protettiva|80|"))
        t = 4_000L
        assertTrue(debounce.shouldQuery("bolla protettiva|80|"))
    }

    @Test
    fun nameConflictForcesLookupOnTheFirstFrame() {
        var t = 0L
        val debounce = CatalogQueryDebounce(
            stableFramesNeeded = 8,
            requeryAfterMs = 2_000L,
            now = { t },
        )
        assertTrue(!debounce.shouldQuery("ostinazione mortale|17|"))
        assertTrue(debounce.forceQuery("ostinazione mortale|17|"))
        t = 100L
        assertTrue(!debounce.forceQuery("ostinazione mortale|17|"))
        assertTrue(!debounce.shouldQuery("ostinazione mortale|17|"))
        t = 2_100L
        assertTrue(debounce.forceQuery("ostinazione mortale|17|"))
    }

    @Test
    fun aDifferentIdResetsTheWait() {
        var t = 0L
        val debounce = CatalogQueryDebounce(
            stableFramesNeeded = 8,
            requeryAfterMs = 2_000L,
            now = { t },
        )
        assertTrue(!debounce.shouldQuery("bolla protettiva|80|"))
        t = 1_500L
        assertTrue(!debounce.shouldQuery("bolla protettiva||"))
        t = 3_600L
        assertTrue(!debounce.shouldQuery("bolla protettiva|80|"))
        t = 5_600L
        assertTrue(debounce.shouldQuery("bolla protettiva|80|"))
    }
}
