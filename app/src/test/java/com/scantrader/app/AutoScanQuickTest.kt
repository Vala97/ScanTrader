package com.scantrader.app

import com.scantrader.app.scan.AutoScanQuick
import com.scantrader.app.scan.ManualShutter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun manualShutterStaysAvailableAndDoesNotAutosave() {
        assertEquals("Scatto manuale", ManualShutter.CONTENT_DESCRIPTION)
        assertEquals(76, ManualShutter.diameterDp(autoScanOn = false))
        assertEquals(52, ManualShutter.diameterDp(autoScanOn = true))
        assertEquals(1f, ManualShutter.alpha(false))
        assertEquals(0.5f, ManualShutter.alpha(true))
        assertTrue(ManualShutter.allowAutoFrame(autoScanOn = true, captureInFlight = false))
        assertFalse(ManualShutter.allowAutoFrame(autoScanOn = true, captureInFlight = true))
        assertFalse(ManualShutter.allowAutoFrame(autoScanOn = false, captureInFlight = false))
        assertFalse(ManualShutter.confirmFromAutoScan(autoScanOn = true, manualShot = true))
        assertTrue(ManualShutter.confirmFromAutoScan(autoScanOn = true, manualShot = false))
        assertFalse(ManualShutter.confirmFromAutoScan(autoScanOn = false, manualShot = false))
        assertFalse(ManualShutter.blockRepeatPrinting(autoMode = false, sameAsLast = true))
        assertTrue(ManualShutter.blockRepeatPrinting(autoMode = true, sameAsLast = true))
        assertFalse(ManualShutter.blockRepeatPrinting(autoMode = true, sameAsLast = false))
    }
}
