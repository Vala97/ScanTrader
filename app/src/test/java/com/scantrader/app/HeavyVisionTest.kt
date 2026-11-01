package com.scantrader.app

import com.scantrader.app.scan.HeavyVision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeavyVisionTest {
    @Test
    fun debounceHoldDoesNotEmbed() {
        assertFalse(
            HeavyVision.embedAlongsideCatalog(
                autoMode = true,
                cooling = false,
                queryCatalog = false,
            ),
        )
        assertFalse(
            HeavyVision.embedAfterCatalog(
                autoMode = true,
                queryCatalog = false,
                hitCount = 0,
            ),
        )
    }

    @Test
    fun cooldownEmbedsWithoutWaitingForAnotherFrame() {
        assertTrue(
            HeavyVision.embedAlongsideCatalog(
                autoMode = true,
                cooling = true,
                queryCatalog = false,
            ),
        )
    }

    @Test
    fun galleryRunsOnlyAfterAnEmptyCatalogQuery() {
        assertFalse(
            HeavyVision.embedAlongsideCatalog(
                autoMode = true,
                cooling = false,
                queryCatalog = true,
            ),
        )
        assertTrue(
            HeavyVision.embedAfterCatalog(
                autoMode = true,
                queryCatalog = true,
                hitCount = 0,
            ),
        )
        assertFalse(
            HeavyVision.embedAfterCatalog(
                autoMode = true,
                queryCatalog = true,
                hitCount = 1,
            ),
        )
    }

    @Test
    fun manualScanStillEmbedsBesideTheCatalog() {
        assertTrue(
            HeavyVision.embedAlongsideCatalog(
                autoMode = false,
                cooling = false,
                queryCatalog = true,
            ),
        )
        assertFalse(
            HeavyVision.embedAfterCatalog(
                autoMode = false,
                queryCatalog = true,
                hitCount = 0,
            ),
        )
    }
}
