package com.scantrader.app

import com.scantrader.app.scan.CameraBitmaps
import com.scantrader.app.scan.CardFrameGeometry
import com.scantrader.app.scan.CardOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun flatPhoneRotation180BecomesThePortrait90ThatWorked() {
        assertEquals(90, CameraBitmaps.rotationForPortrait(180, 1280, 960))
        assertEquals(90, CameraBitmaps.rotationForPortrait(0, 1280, 960))
        assertEquals(90, CameraBitmaps.rotationForPortrait(90, 1280, 960))
        assertEquals(270, CameraBitmaps.rotationForPortrait(270, 1280, 960))
        // Already portrait: keep the reported quarter turn.
        assertEquals(180, CameraBitmaps.rotationForPortrait(180, 574, 1280))

        // The 21:53 crop was this center band mapped at 180 → 1280x574.
        val landscape = CameraBitmaps.mapCropRect(
            left = 0,
            top = 193,
            right = 1280,
            bottom = 767,
            rotationDegrees = 180,
            width = 1280,
            height = 960,
        )
        assertEquals(1280, landscape.width)
        assertEquals(574, landscape.height)

        // The same band at the portrait rotation is the 21:32 frame, 574x1280.
        val portrait = CameraBitmaps.mapCropRect(
            left = 0,
            top = 193,
            right = 1280,
            bottom = 767,
            rotationDegrees = CameraBitmaps.rotationForPortrait(180, 1280, 960),
            width = 1280,
            height = 960,
        )
        assertEquals(574, portrait.width)
        assertEquals(1280, portrait.height)

        val upright = CardFrameGeometry.frameIn(portrait.width.toFloat(), portrait.height.toFloat())
        assertTrue(upright.height > upright.width)
        val sideways = CardFrameGeometry.frameIn(landscape.width.toFloat(), landscape.height.toFloat())
        assertTrue(sideways.height < sideways.width)

        val titleSide = CardOrientation.titleMaxSide(
            width = 1000,
            height = 1400,
            floor = 480,
            ceiling = 960,
            titleHeightFraction = 0.14f,
            minTitlePx = 96,
        )
        assertTrue(titleSide > 480)
        assertTrue(titleSide <= 960)
    }
}
