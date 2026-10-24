package com.scantrader.app

import com.scantrader.app.data.catalog.CatalogQueryDebounce
import com.scantrader.app.data.catalog.CatalogQueryDebounce.Frame
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

    @Test
    fun kilowattrelLogSequenceQueriesWithinThreeFrames() {
        // Device log: name and set flicker, cn=72 and count=217 do not.
        // The old name|cn|set key reset every frame, so the 2s fallback never fired.
        var t = 0L
        val debounce = CatalogQueryDebounce(
            stableFramesNeeded = 2,
            requeryAfterMs = 2_000L,
            now = { t },
        )
        val frames = listOf(
            frame("Kilowattrel", "72", "dasc", 217),
            frame("Kilowattrel Kissara", null, null, 217),
            frame("Kilowattrel ai Kissara", "72", "asc", 217),
            frame("Kilowattrel Kissara", "72", "oasc", 217),
            frame("Kilowattrel Kissara", "72", "dasc", 217),
            frame("Kilowattrel ai Kissara", "72", null, 217),
            frame("Kilowattrel Kissara", "72", "oasc", 217),
            frame("FASEl", "72", "asch", 217),
            frame("Kilowattrel Kissaral", "72", "asc", 217),
            frame("CASEl", "72", "asca", 217),
            frame("Wattrel di Kissara", "72", "dasc", 217),
            frame("Kilowattrel di Kisara", null, null, 217),
            frame("Kilowattrel Kisara", "72", "asc", 217),
            frame("Kilowattrel ai Kisara", "72", "asc", 217),
            frame("Kilowattrel Kissara", "72", "asch", 217),
        )
        var firedAt = -1
        var voted: Frame? = null
        frames.forEachIndexed { index, read ->
            t += 10
            if (debounce.shouldQuery(read) && firedAt < 0) {
                firedAt = index
                voted = debounce.votedFrame()
            }
        }
        assertTrue("lookup fired at frame $firedAt", firedAt in 1..2)
        assertEquals("72", voted?.collectorNumber)
        assertEquals(217, voted?.setCardCount)
        assertEquals("asc", voted?.setCode)
        assertEquals("Kilowattrel di Kissara", voted?.name)
    }

    @Test
    fun alternatingOcrDoesNotResetTheTwoSecondFallback() {
        var t = 0L
        val debounce = CatalogQueryDebounce(
            stableFramesNeeded = 8,
            requeryAfterMs = 2_000L,
            now = { t },
        )
        assertTrue(!debounce.shouldQuery(frame("Kilowattrel", "72", "dasc", 217)))
        t = 1_999L
        assertTrue(!debounce.shouldQuery(frame("Kilowattrel Kissara", "72", "oasc", 217)))
        t = 2_000L
        assertTrue(debounce.shouldQuery(frame("Kilowattrel ai Kissara", "72", "asch", 217)))
        val voted = debounce.votedFrame()
        assertEquals("72", voted?.collectorNumber)
        assertEquals(217, voted?.setCardCount)
        assertEquals("asc", voted?.setCode)
        assertTrue(voted?.name.orEmpty().contains("Kilowattrel"))
    }

    @Test
    fun threeFramesOfTheSameCardQueryEvenWhenTheNameNeverRepeats() {
        var t = 0L
        val debounce = CatalogQueryDebounce(
            stableFramesNeeded = 8,
            requeryAfterMs = 2_000L,
            now = { t },
        )
        assertTrue(!debounce.shouldQuery(frame("Kilowattrel di Kissara", "72", "dasc", 217)))
        t = 20L
        assertTrue(!debounce.shouldQuery(frame("FASEl", "72", "asch", 217)))
        t = 40L
        assertTrue(debounce.shouldQuery(frame("Kilowattrel Kissara", "72", "oasc", 217)))
        assertEquals("asc", debounce.votedFrame()?.setCode)
        assertTrue(debounce.votedFrame()?.name.orEmpty().contains("Kilowattrel"))
    }

    private fun frame(name: String?, cn: String?, set: String?, count: Int) = Frame(
        name = name,
        collectorNumber = cn,
        setCode = set,
        setCardCount = count,
        pokemon = true,
    )
}
