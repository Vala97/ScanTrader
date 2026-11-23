package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.scan.OcrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The language code beside the set on the Magic collector line. Language only. */
class MtgCollectorLanguageTest {
    private fun parse(bottom: String, title: String = "Fulmine") = OcrParser.parse(
        rawText = title,
        game = Game.MTG,
        bottomPriorityText = bottom,
        namePriorityText = title,
    )

    @Test
    fun modernTwoLineCollectorReadsTheCode() {
        val it = parse("0309 R\nLCC • IT  Mark Poole")
        assertEquals("it", it.collectorLang)
        assertEquals("309", it.collectorNumber)
        assertEquals("lcc", it.setCode)
        // The catalog lookup input is unchanged: no language came from the identity patterns.
        assertNull(it.printedLang)
    }

    @Test
    fun oneLineCollectorReadsTheCode() {
        val it = parse("0181 • DFT • IT")
        assertEquals("it", it.collectorLang)
        assertEquals("181", it.collectorNumber)
        assertEquals("dft", it.setCode)
    }

    @Test
    fun everySupportedCode() {
        assertEquals("es", parse("0123 U\nDMU • ES  Artist").collectorLang)
        assertEquals("es", parse("0123 U\nDMU • SP  Artist").collectorLang)
        assertEquals("en", parse("0123 U\nDMU • EN  Artist").collectorLang)
        assertEquals("ja", parse("0050 C\nNEO • JP  Artist").collectorLang)
        assertEquals("zhs", parse("0050 C\nWOE • CS  Artist").collectorLang)
        assertEquals("zht", parse("0050 C\nWOE • CT  Artist").collectorLang)
        assertEquals("de", parse("0050 C\nWOE • DE  Artist").collectorLang)
        assertEquals("fr", parse("0050 C\nWOE * FR  Artist").collectorLang)
        assertEquals("pt", parse("0050 C\nWOE • PT  Artist").collectorLang)
    }

    @Test
    fun ocrNoiseOnTheItalianCode() {
        assertEquals("it", parse("0309 R\nLCC • 1T  Mark Poole").collectorLang)
        assertEquals("it", parse("0309 R\nLCC*lT Mark Poole").collectorLang)
        assertEquals("it", parse("0309 R\nLCCIT Mark Poole").collectorLang)
    }

    @Test
    fun theGluedArtistFormStillSetsPrintedLang() {
        val glued = parse("105/269 C\nELDITTAYLOR INGNARSSON", title = "Maestro di Spada Invaghi")
        assertEquals("it", glued.printedLang)
        assertEquals("it", glued.collectorLang)
    }

    @Test
    fun oldFramesHaveNoCode() {
        assertNull(parse("123/249 C\nIllus. Mark Poole").collectorLang)
        assertNull(parse("Illus. Mark Poole\n™ & © 1995 Wizards of the Coast").collectorLang)
    }

    @Test
    fun aStrayItIsNotACode() {
        // Not a known set and no printed bullet.
        assertNull(parse("MAKE IT COUNT").collectorLang)
        assertNull(parse("0309 R\nLCC").collectorLang)
    }
}
