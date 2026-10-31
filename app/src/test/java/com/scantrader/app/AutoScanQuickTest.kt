package com.scantrader.app

import com.scantrader.app.scan.AutoScanQuick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AutoScanQuickTest {
    @Test
    fun messagesAndContentDescriptionFollowTheSwitch() {
        assertEquals("Auto-scan attivo", AutoScanQuick.message(true))
        assertEquals("Auto-scan disattivato", AutoScanQuick.message(false))
        assertEquals(AutoScanQuick.message(true), AutoScanQuick.contentDescription(true))
        assertEquals(AutoScanQuick.message(false), AutoScanQuick.contentDescription(false))
        assertFalse(AutoScanQuick.message(true).contains("—"))
        assertFalse(AutoScanQuick.message(false).contains("–"))
    }

    @Test
    fun openConfirmationKeepsTheModeItWasOpenedWith() {
        assertEquals(true, AutoScanQuick.modeForCard(setting = false, openedWith = true, confirmOpen = true))
        assertEquals(false, AutoScanQuick.modeForCard(setting = true, openedWith = false, confirmOpen = true))
        assertEquals(false, AutoScanQuick.modeForCard(setting = false, openedWith = true, confirmOpen = false))
        assertEquals(true, AutoScanQuick.modeForCard(setting = true, openedWith = false, confirmOpen = false))
    }
}
