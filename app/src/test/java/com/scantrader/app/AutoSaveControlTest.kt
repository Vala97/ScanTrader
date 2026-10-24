package com.scantrader.app

import com.scantrader.app.ui.confirm.AutoSaveControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSaveControlTest {
    @Test
    fun everyOptionInteractionCancelsTheCountdown() {
        AutoSaveControl.Gesture.entries.forEach { gesture ->
            assertTrue(AutoSaveControl.onUserInteraction(offered = true, gesture))
            val ui = AutoSaveControl.present(
                offered = true,
                cancelled = true,
                selectedMatchesGate = gesture != AutoSaveControl.Gesture.CANDIDATE,
                secondsLeft = 4,
                saving = false,
            )
            assertFalse(ui.armed)
            assertFalse(ui.countdownVisible)
            assertEquals(AutoSaveControl.CANCELLED_LABEL, ui.statusLabel)
            assertEquals("Salvataggio automatico annullato", ui.statusLabel)
            assertEquals(AutoSaveControl.MANUAL_SAVE_LABEL, ui.saveButtonLabel)
            assertEquals("Salva in collezione", ui.saveButtonLabel)
        }
    }

    @Test
    fun interactionDoesNothingWhenAutoSaveWasNotOffered() {
        assertFalse(AutoSaveControl.onUserInteraction(offered = false, AutoSaveControl.Gesture.LANGUAGE))
        val ui = AutoSaveControl.present(
            offered = false,
            cancelled = true,
            selectedMatchesGate = true,
            secondsLeft = 4,
            saving = false,
        )
        assertNull(ui.statusLabel)
        assertEquals("OK · Salva in collezione", ui.saveButtonLabel)
    }

    @Test
    fun armedCountdownStaysUntilTheUserTouchesTheCard() {
        val ui = AutoSaveControl.present(
            offered = true,
            cancelled = false,
            selectedMatchesGate = true,
            secondsLeft = 5,
            saving = false,
        )
        assertTrue(ui.armed)
        assertTrue(ui.countdownVisible)
        assertNull(ui.statusLabel)
        assertEquals("Salva tra 5s…", ui.saveButtonLabel)
    }

    @Test
    fun nextScanRearmsAutoSave() {
        assertFalse(AutoSaveControl.rearmedCancelled())
        val ui = AutoSaveControl.present(
            offered = true,
            cancelled = AutoSaveControl.rearmedCancelled(),
            selectedMatchesGate = true,
            secondsLeft = 3,
            saving = false,
        )
        assertTrue(ui.countdownVisible)
        assertNull(ui.statusLabel)
        assertEquals("Salva tra 3s…", ui.saveButtonLabel)
    }
}
