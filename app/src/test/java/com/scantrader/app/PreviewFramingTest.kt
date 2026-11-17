package com.scantrader.app

import com.scantrader.app.scan.PreviewFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFramingTest {
    @Test
    fun bindWaitsForARealSizeAndAClosedKeyboard() {
        assertFalse(
            PreviewFraming.shouldBind(
                granted = true,
                gamePicked = true,
                keyboardOpen = false,
                width = 0,
                height = 2400,
                alreadyBound = false,
                boundCameraId = null,
                requestedCameraId = null,
                boundWidth = 0,
                boundHeight = 0,
            ),
        )
        assertFalse(
            PreviewFraming.shouldBind(
                granted = true,
                gamePicked = true,
                keyboardOpen = true,
                width = 1080,
                height = 1600,
                alreadyBound = false,
                boundCameraId = null,
                requestedCameraId = null,
                boundWidth = 0,
                boundHeight = 0,
            ),
        )
        assertTrue(
            PreviewFraming.shouldBind(
                granted = true,
                gamePicked = true,
                keyboardOpen = false,
                width = 1080,
                height = 2400,
                alreadyBound = false,
                boundCameraId = null,
                requestedCameraId = null,
                boundWidth = 0,
                boundHeight = 0,
            ),
        )
    }

    @Test
    fun aSettledSizeReplacesThePreviousGuideAndTheKeyboardDoesNot() {
        assertNull(PreviewFraming.guideSize(1080f, 1800f, keyboardOpen = true))
        assertEquals(1080f to 2400f, PreviewFraming.guideSize(1080f, 2400f, keyboardOpen = false))
        assertFalse(
            PreviewFraming.acceptGuideMeasurement(
                width = 1080f,
                height = 2400f,
                previousWidth = 0f,
                previousHeight = 0f,
                keyboardOpen = false,
            ),
        )
        assertTrue(
            PreviewFraming.acceptGuideMeasurement(
                width = 1080f,
                height = 2400f,
                previousWidth = 1080f,
                previousHeight = 2400f,
                keyboardOpen = false,
            ),
        )
        assertFalse(
            PreviewFraming.acceptGuideMeasurement(
                width = 1080f,
                height = 1800f,
                previousWidth = 1080f,
                previousHeight = 1800f,
                keyboardOpen = true,
            ),
        )
        assertTrue(
            PreviewFraming.acceptGuideMeasurement(
                width = 1080f,
                height = 2200f,
                previousWidth = 1080f,
                previousHeight = 2200f,
                keyboardOpen = false,
            ),
        )
    }

    @Test
    fun frameLogNamesTheViewRotationCropZoomAndGuide() {
        val line = PreviewFraming.frameLogLine(
            viewWidth = 1080,
            viewHeight = 2400,
            previewRotation = 0,
            cropLeft = 0,
            cropTop = 193,
            cropRight = 1280,
            cropBottom = 767,
            zoom = 2f,
            guideLeft = 97f,
            guideTop = 534f,
            guideWidth = 886f,
            guideHeight = 1237f,
        )
        assertEquals(
            "frame: view=1080x2400 rotation=0 crop=[0,193,1280,767] zoom=2.00 guide=[97,534 886x1237]",
            line,
        )
    }
}