package com.scantrader.app

import com.scantrader.app.scan.HeavyVision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeavyVisionTest {
    @Test
    fun heldCatalogStillRunsTheGalleryWhenIdentityIsReady() {
        assertFalse(
            HeavyVision.skip(
                autoMode = true,
                idReady = true,
                queryCatalog = false,
                neuralChoosesPrinting = false,
            ),
        )
    }

    @Test
    fun catalogQuerySkipsTheGalleryWhenIdentityIsReady() {
        assertTrue(
            HeavyVision.skip(
                autoMode = true,
                idReady = true,
                queryCatalog = true,
                neuralChoosesPrinting = false,
            ),
        )
    }

    @Test
    fun manualScanMissingIdentityAndCardTraderKeepTheGallery() {
        assertFalse(
            HeavyVision.skip(
                autoMode = false,
                idReady = true,
                queryCatalog = true,
                neuralChoosesPrinting = false,
            ),
        )
        assertFalse(
            HeavyVision.skip(
                autoMode = true,
                idReady = false,
                queryCatalog = true,
                neuralChoosesPrinting = false,
            ),
        )
        assertFalse(
            HeavyVision.skip(
                autoMode = true,
                idReady = true,
                queryCatalog = true,
                neuralChoosesPrinting = true,
            ),
        )
    }
}
