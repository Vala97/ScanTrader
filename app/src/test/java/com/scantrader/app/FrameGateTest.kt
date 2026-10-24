package com.scantrader.app

import com.scantrader.app.scan.FrameGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGateTest {
    @Test
    fun flatFrameIsBlur() {
        val grid = IntArray(FrameGate.SAMPLE_WIDTH * FrameGate.SAMPLE_HEIGHT) { 40 }
        val blur = FrameGate.blurScore(grid, FrameGate.SAMPLE_WIDTH)
        assertTrue(blur < FrameGate.BLUR_MIN)
        assertEquals("blur", FrameGate.skipReason(blur, motion = null, sincePreviousMs = 100))
    }

    @Test
    fun checkerboardIsSharp() {
        val width = FrameGate.SAMPLE_WIDTH
        val grid = IntArray(width * FrameGate.SAMPLE_HEIGHT) { index ->
            val x = index % width
            val y = index / width
            if ((x + y) % 2 == 0) 0 else 255
        }
        val blur = FrameGate.blurScore(grid, width)
        assertTrue(blur > FrameGate.BLUR_MIN)
        assertNull(FrameGate.skipReason(blur, motion = 0.0, sincePreviousMs = 100))
    }

    @Test
    fun recentMotionSkips() {
        val previous = IntArray(8) { 0 }
        val current = IntArray(8) { 80 }
        val motion = FrameGate.motionMeanAbs(previous, current)
        assertTrue(motion > FrameGate.MOTION_MAX)
        assertEquals("motion", FrameGate.skipReason(20.0, motion, sincePreviousMs = 200))
    }

    @Test
    fun staleGapDoesNotCountAsMotion() {
        assertNull(FrameGate.skipReason(blur = 20.0, motion = 80.0, sincePreviousMs = 2_000))
    }
}
