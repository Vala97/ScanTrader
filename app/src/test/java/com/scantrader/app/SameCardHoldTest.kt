package com.scantrader.app

import com.scantrader.app.scan.FrameGate
import com.scantrader.app.scan.SameCardHold
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameCardHoldTest {
    @Test
    fun identicalGridStaysPaused() {
        val grid = IntArray(FrameGate.SAMPLE_WIDTH * FrameGate.SAMPLE_HEIGHT) { index -> index % 40 }
        assertTrue(SameCardHold.unchanged(grid, grid.copyOf()))
    }

    @Test
    fun changedGridResumes() {
        val width = FrameGate.SAMPLE_WIDTH
        val previous = IntArray(width * FrameGate.SAMPLE_HEIGHT) { 20 }
        val current = IntArray(previous.size) { 20 + SameCardHold.CHANGE_MIN.toInt() }
        assertFalse(SameCardHold.unchanged(previous, current))
        assertTrue(FrameGate.motionMeanAbs(previous, current) >= SameCardHold.CHANGE_MIN)
    }

    @Test
    fun missingPreviousDoesNotPause() {
        val current = IntArray(8) { 10 }
        assertFalse(SameCardHold.unchanged(null, current))
    }
}
