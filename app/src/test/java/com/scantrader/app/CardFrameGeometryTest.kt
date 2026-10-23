package com.scantrader.app

import com.scantrader.app.scan.CardFrameGeometry
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.min

class CardFrameGeometryTest {
    @Test
    fun frameKeepsCardAspectOnATallPreview() {
        val viewW = 1080f
        val viewH = 2400f
        val rect = CardFrameGeometry.frameIn(viewW, viewH)
        val expectedW = viewW * CardFrameGeometry.WIDTH_FRACTION
        val expectedH = min(
            expectedW / CardFrameGeometry.ASPECT,
            viewH * CardFrameGeometry.MAX_HEIGHT_FRACTION,
        )
        assertEquals(expectedW, rect.width, 0.01f)
        assertEquals(expectedH, rect.height, 0.01f)
        assertEquals(expectedW / CardFrameGeometry.ASPECT, rect.height, 0.01f)
        assertEquals((viewW - expectedW) / 2f, rect.left, 0.01f)
    }

    @Test
    fun frameCapsHeightOnAShortPreview() {
        val rect = CardFrameGeometry.frameIn(1000f, 500f)
        assertEquals(500f * CardFrameGeometry.MAX_HEIGHT_FRACTION, rect.height, 0.01f)
    }

    @Test
    fun repeatedFramesMatchSoTheCropStaysOnTheGuide() {
        val first = CardFrameGeometry.frameIn(1080f, 2400f)
        val second = CardFrameGeometry.frameIn(1080f, 2400f)
        assertEquals(first, second)
    }
}
