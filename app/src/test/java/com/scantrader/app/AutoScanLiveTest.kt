package com.scantrader.app

import com.scantrader.app.scan.AutoScanLive
import com.scantrader.app.scan.FramedKind
import org.junit.Assert.assertEquals
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
        val busy = AutoScanLive.framed(sameAsSaved = false, recognizedName = "  ")
        assertEquals(FramedKind.ANALYZING, busy.kind)
        assertNull(busy.name)
    }
}
