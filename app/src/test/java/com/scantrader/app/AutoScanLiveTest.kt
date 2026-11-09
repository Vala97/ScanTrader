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
        assertFalse(AutoScanLive.showOpenLast(named, 12L))
        val busy = AutoScanLive.framed(sameAsSaved = false, recognizedName = "  ")
        assertEquals(FramedKind.ANALYZING, busy.kind)
        assertNull(busy.name)
        assertFalse(AutoScanLive.showOpenLast(busy, 12L))
    }

    @Test
    fun openLastOnlyForTheKnownSameCard() {
        val saved = AutoScanLive.framed(sameAsSaved = true, recognizedName = "Boughside Wanderers")
        assertEquals(FramedKind.SAVED, saved.kind)
        assertEquals(42L, openId(saved, 42L))
        val analyzingSame = AutoScanLive.framed(
            sameAsSaved = false,
            recognizedName = null,
            sameIdentity = true,
        )
        assertEquals(FramedKind.ANALYZING, analyzingSame.kind)
        assertEquals(42L, openId(analyzingSame, 42L))
        val unknown = AutoScanLive.framed(sameAsSaved = false, recognizedName = null, sameIdentity = false)
        assertNull(openId(unknown, 42L))
        val other = AutoScanLive.framed(sameAsSaved = false, recognizedName = "Sol Ring", sameIdentity = true)
        assertEquals(FramedKind.NEW, other.kind)
        assertNull(openId(other, 42L))
        assertNull(openId(saved, null))
        assertNull(openId(saved, 0L))
    }

    private fun openId(framed: com.scantrader.app.scan.FramedCard, entryId: Long?): Long? =
        if (AutoScanLive.showOpenLast(framed, entryId)) entryId else null
}
