package com.scantrader.app

import com.scantrader.app.scan.ZoomFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomFramingTest {
    @Test
    fun defaultRatioAfterBindDoesNotReplaceStoredFraming() {
        assertTrue(ZoomFraming.ignoreReportedZoom(stored = 2.4f, reported = 1f, restoreArmed = true))
        assertTrue(ZoomFraming.ignoreReportedZoom(stored = 0.5f, reported = 1f, restoreArmed = true))
        assertFalse(ZoomFraming.ignoreReportedZoom(stored = 2.4f, reported = 2.37f, restoreArmed = true))
        assertFalse(ZoomFraming.ignoreReportedZoom(stored = 2.4f, reported = 1f, restoreArmed = false))
    }

    @Test
    fun clampsRestoreRatioToTheCameraRange() {
        assertEquals(5f, ZoomFraming.clamp(8f, min = 0.5f, max = 5f))
        assertEquals(0.5f, ZoomFraming.clamp(0.2f, min = 0.5f, max = 5f))
        assertEquals(1.8f, ZoomFraming.clamp(1.8f, min = 0.5f, max = 5f))
        assertNull(ZoomFraming.clamp(0f, min = 0.5f, max = 5f))
    }

    @Test
    fun unbindJumpToOneIsNotAPinch() {
        assertTrue(ZoomFraming.isUnbindReset(stored = 2.4f, reported = 1f))
        assertTrue(ZoomFraming.isUnbindReset(stored = 0.5f, reported = 1f))
        assertFalse(ZoomFraming.isUnbindReset(stored = 1f, reported = 1f))
        assertFalse(ZoomFraming.isUnbindReset(stored = 2.4f, reported = 2.2f))
    }
}
