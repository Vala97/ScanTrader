package com.scantrader.app

import com.scantrader.app.vision.ImageFingerprint
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageFingerprintTest {
    @Test
    fun identicalHashesHaveFullSimilarity() {
        assertEquals(1f, ImageFingerprint.hammingSimilarity(0xABCDL, 0xABCDL), 0.001f)
    }

    @Test
    fun oneBitDifferenceIsNearFull() {
        assertEquals(1f - 1f / 64f, ImageFingerprint.hammingSimilarity(0L, 1L), 0.001f)
    }

    @Test
    fun histogramSelfSimilarityIsOne() {
        val hist = FloatArray(64) { if (it == 3) 1f else 0f }
        assertEquals(1f, ImageFingerprint.histogramSimilarity(hist, hist), 0.001f)
    }
}
