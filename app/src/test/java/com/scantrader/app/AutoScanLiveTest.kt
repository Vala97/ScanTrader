package com.scantrader.app

import com.scantrader.app.scan.AutoScanLive
import com.scantrader.app.scan.FramedKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AutoScanLiveTest {
    @Test
    fun samePrintingIsAlreadySaved() {
        val framed = AutoScanLive.framed(sameAsSaved = true, recognizedName = "Release the Ants")
        assertEquals(FramedKind.SAVED, framed.kind)
        assertEquals("Release the Ants", framed.name)
    }

    @Test
    fun differentCardShowsTheNewNameOrAnalyzing() {
        val named = AutoScanLive.framed(sameAsSaved = false, recognizedName = "Rubacapre")
        assertEquals(FramedKind.NEW, named.kind)
        assertEquals("Rubacapre", named.name)
        assertFalse(AutoScanLive.showApri(hasLastSaved = false))
        val busy = AutoScanLive.framed(sameAsSaved = false, recognizedName = "  ")
        assertEquals(FramedKind.ANALYZING, busy.kind)
        assertNull(busy.name)
        assertFalse(AutoScanLive.showApri(hasLastSaved = false))
    }

    @Test
    fun apriStaysAvailableForAnyCardInTheFrame() {
        val different = AutoScanLive.framed(sameAsSaved = false, recognizedName = "Sol Ring")
        assertEquals(FramedKind.NEW, different.kind)
        assertEquals(true, AutoScanLive.showApri(hasLastSaved = true))
        assertEquals(42L, AutoScanLive.savedEntryId(42L, 7L))
        val busy = AutoScanLive.framed(sameAsSaved = false, recognizedName = null)
        assertEquals(FramedKind.ANALYZING, busy.kind)
        assertEquals(true, AutoScanLive.showApri(hasLastSaved = true))
        assertEquals(true, AutoScanLive.showLastCardBar(hasLastSaved = true, busy))
        assertNull(AutoScanLive.savedEntryId(null, 7L))
        assertNull(AutoScanLive.savedEntryId(0L, 7L))
        assertNull(AutoScanLive.savedEntryId(42L, null))
        assertFalse(AutoScanLive.showApri(hasLastSaved = false))
    }

    @Test
    fun lastCardBarHidesWhenItWouldOnlyRepeatTheAnalysis() {
        val analyzing = AutoScanLive.framed(sameAsSaved = false, recognizedName = null)
        assertFalse(AutoScanLive.showFrameStatus(analyzing.kind))
        assertFalse(AutoScanLive.showLastCardBar(hasLastSaved = false, analyzing))

        val sameWhileBusy = AutoScanLive.framed(sameAsSaved = false, recognizedName = null, sameIdentity = true)
        assertEquals(true, AutoScanLive.showLastCardBar(hasLastSaved = true, sameWhileBusy))
        assertEquals(true, AutoScanLive.showApri(hasLastSaved = true))

        val saved = AutoScanLive.framed(sameAsSaved = true, recognizedName = "Boughside Wanderers")
        assertEquals(true, AutoScanLive.showFrameStatus(saved.kind))
        assertEquals(true, AutoScanLive.showLastCardBar(hasLastSaved = false, saved))
    }

    @Test
    fun collapsedBarStartsOpenAndTheToggleIsRemembered() {
        assertFalse(AutoScanLive.lastCardBarCollapsed(stored = null))
        assertFalse(AutoScanLive.lastCardBarCollapsed(stored = false))
        assertEquals(true, AutoScanLive.lastCardBarCollapsed(stored = true))
        assertEquals(true, AutoScanLive.toggleLastCardBar(collapsed = false))
        assertEquals(false, AutoScanLive.toggleLastCardBar(collapsed = true))
    }

    @Test
    fun frameCornersFollowSearchAnalysisAndRecognition() {
        assertEquals(
            AutoScanLive.FramePhase.ANALYZING,
            AutoScanLive.framePhase(processing = true, autoStatus = null, framed = FramedKind.ANALYZING),
        )
        assertEquals(
            AutoScanLive.FramePhase.ANALYZING,
            AutoScanLive.framePhase(processing = false, autoStatus = "Analisi in corso…", framed = FramedKind.ANALYZING),
        )
        assertEquals(
            AutoScanLive.FramePhase.RECOGNIZED,
            AutoScanLive.framePhase(processing = false, autoStatus = "Carta trovata", framed = FramedKind.SAVED),
        )
        assertEquals(
            AutoScanLive.FramePhase.RECOGNIZED,
            AutoScanLive.framePhase(processing = false, autoStatus = null, framed = FramedKind.NEW),
        )
        assertEquals(
            AutoScanLive.FramePhase.SEARCHING,
            AutoScanLive.framePhase(processing = false, autoStatus = "Inquadra la carta…", framed = FramedKind.ANALYZING),
        )
    }
}
