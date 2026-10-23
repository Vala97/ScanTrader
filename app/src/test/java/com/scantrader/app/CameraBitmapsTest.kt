package com.scantrader.app

import com.scantrader.app.scan.CameraBitmaps
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraBitmapsTest {
    @Test
    fun mapsCropRectThrough90DegreeRotation() {
        // Buffer 1280x960, crop full frame → after 90° CW becomes 960x1280 space.
        val mapped = CameraBitmaps.mapCropRect(
            left = 0,
            top = 0,
            right = 1280,
            bottom = 960,
            rotationDegrees = 90,
            width = 1280,
            height = 960,
        )
        assertEquals(0, mapped.left)
        assertEquals(0, mapped.top)
        assertEquals(960, mapped.right)
        assertEquals(1280, mapped.bottom)
    }

    @Test
    fun mapsPartialCropRectThrough90() {
        val mapped = CameraBitmaps.mapCropRect(
            left = 100,
            top = 50,
            right = 500,
            bottom = 400,
            rotationDegrees = 90,
            width = 1280,
            height = 960,
        )
        assertEquals(960 - 400, mapped.left)
        assertEquals(100, mapped.top)
        assertEquals(960 - 50, mapped.right)
        assertEquals(500, mapped.bottom)
    }
}
