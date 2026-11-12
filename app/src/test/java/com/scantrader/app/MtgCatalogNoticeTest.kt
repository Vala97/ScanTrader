package com.scantrader.app

import com.scantrader.app.vision.MtgCatalogNotice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtgCatalogNoticeTest {
    private val day = MtgCatalogNotice.CHECK_INTERVAL_MS

    @Test
    fun indexCheckWaitsADayAfterASuccessfulRead() {
        assertTrue(MtgCatalogNotice.shouldCheck(lastCheckAtMs = 0L, nowMs = day))
        assertTrue(MtgCatalogNotice.shouldCheck(lastCheckAtMs = -1L, nowMs = 0L))
        val last = 1_000L
        assertFalse(MtgCatalogNotice.shouldCheck(last, last + day - 1L))
        assertTrue(MtgCatalogNotice.shouldCheck(last, last + day))
    }

    @Test
    fun noticeOnlyWhenAnInstalledCatalogIsOlder() {
        assertFalse(
            MtgCatalogNotice.noticeVisible(
                catalogInstalled = false,
                installedVersion = null,
                remoteVersion = "2026.11.13",
            ),
        )
        assertFalse(
            MtgCatalogNotice.noticeVisible(
                catalogInstalled = true,
                installedVersion = "2026.07.09",
                remoteVersion = "2026.07.09",
            ),
        )
        assertFalse(
            MtgCatalogNotice.noticeVisible(
                catalogInstalled = true,
                installedVersion = "2026.11.13",
                remoteVersion = "2026.07.09",
            ),
        )
        assertTrue(
            MtgCatalogNotice.noticeVisible(
                catalogInstalled = true,
                installedVersion = "2026.07.09",
                remoteVersion = "2026.11.13",
            ),
        )
        assertTrue(
            MtgCatalogNotice.noticeVisible(
                catalogInstalled = true,
                installedVersion = null,
                remoteVersion = "2026.11.13",
            ),
        )
    }
}
