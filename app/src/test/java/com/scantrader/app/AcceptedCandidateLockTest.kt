package com.scantrader.app

import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.AcceptedCandidateLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptedCandidateLockTest {
    @Test
    fun sameCardStaysLockedUntilTheOverlayReleasesIt() {
        val lock = AcceptedCandidateLock()
        val maxus = ParsedCardText("Maxus del Team Rocket", "176", "dri", 182)
        assertTrue(!lock.shouldIgnore(maxus))
        lock.lock("sv10-176", "176", "Maxus del Team Rocket")
        assertTrue(lock.shouldIgnore(maxus))
        assertTrue(lock.shouldIgnore(ParsedCardText("Maxus del Team Rocket", null, null)))
        assertEquals("sv10-176", lock.key)
        lock.clear()
        assertNull(lock.key)
        assertFalse(lock.shouldIgnore(maxus))
    }

    @Test
    fun aDifferentNameOrCollectorReleasesImmediately() {
        val lock = AcceptedCandidateLock()
        lock.lock("base1-43", "43", "Abra")
        val machop = ParsedCardText("Machop", "52", "base", 102)
        assertFalse(lock.shouldIgnore(machop))
        assertNull(lock.key)
    }

    @Test
    fun blankFramesDoNotReleaseUntilTheyStay() {
        val lock = AcceptedCandidateLock(clearFramesNeeded = 3)
        lock.lock("base1-43", "43", "Abra")
        val blank = ParsedCardText(null, null, null, null)
        assertTrue(lock.shouldIgnore(blank))
        assertTrue(lock.shouldIgnore(blank))
        assertFalse(lock.shouldIgnore(blank))
        assertNull(lock.key)
    }
}
