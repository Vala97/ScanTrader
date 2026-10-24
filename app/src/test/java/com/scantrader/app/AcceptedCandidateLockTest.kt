package com.scantrader.app

import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.AcceptedCandidateLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptedCandidateLockTest {
    @Test
    fun acceptedCardIgnoresLaterFramesUntilADifferentCardStays() {
        val lock = AcceptedCandidateLock()
        val maxus = ParsedCardText("Maxus del Team Rocket", "176", "dri", 182)
        val other = ParsedCardText("Other", "144", "sv10", 182)
        assertTrue(!lock.shouldIgnore(maxus))
        lock.lock("sv10-176", "176", "Maxus del Team Rocket")
        assertTrue(lock.shouldIgnore(maxus))
        assertTrue(lock.shouldIgnore(ParsedCardText("Maxus del Team Rocket", null, null)))
        assertEquals("sv10-176", lock.key)
        assertTrue(lock.shouldIgnore(other))
        assertTrue(lock.shouldIgnore(other))
        assertTrue(!lock.shouldIgnore(other))
        assertNull(lock.key)
    }
}
