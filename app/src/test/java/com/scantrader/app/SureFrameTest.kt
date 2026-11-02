package com.scantrader.app

import com.scantrader.app.scan.SureFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SureFrameTest {
    @Test
    fun firstFrameWithNameAndCollectorSkipsDebounce() {
        assertTrue(
            SureFrame.skipDebounce("Bolla Protettiva", "80", confidence = null, strict = false),
        )
        assertTrue(
            SureFrame.skipDebounce("Volare", "42", confidence = 0.95f, strict = false),
        )
        assertTrue(
            SureFrame.skipDebounce("Volare", "42", confidence = 0.4f, strict = true),
        )
    }

    @Test
    fun weakScoreOrMissingFieldStillWaits() {
        assertFalse(
            SureFrame.skipDebounce("Volare", "42", confidence = 0.94f, strict = false),
        )
        assertFalse(SureFrame.skipDebounce("Volare", null, confidence = null, strict = true))
        assertFalse(SureFrame.skipDebounce(null, "80", confidence = 0.99f, strict = true))
        assertFalse(SureFrame.skipDebounce("er", "80", confidence = null, strict = false))
        assertFalse(SureFrame.skipDebounce("Flight", "2006", confidence = null, strict = false))
    }
}
