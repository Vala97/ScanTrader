package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.scan.OcrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrParserTest {
    @Test
    fun parsesMtgCollectorAndSet() {
        val text = """
            Mountain
            Basic Land — Mountain
            64 • BLB • EN
        """.trimIndent()
        val parsed = OcrParser.parse(text, Game.MTG)
        assertEquals("Mountain", parsed.nameGuess)
        assertEquals("64", parsed.collectorNumber)
        assertEquals("blb", parsed.setCode)
    }

    @Test
    fun parsesModernItalianBottomLineWithLeadingZeros() {
        val full = """
            Entrata Scortese
            Creatura — Orco
        """.trimIndent()
        val bottom = "0181 • DFT • IT"
        val parsed = OcrParser.parse(full, Game.MTG, bottom)
        assertEquals("Entrata Scortese", parsed.nameGuess)
        assertEquals("181", parsed.collectorNumber)
        assertEquals("dft", parsed.setCode)
    }

    @Test
    fun parsesMtgSpacedBottomLine() {
        val bottom = "0123 DSK IT"
        val parsed = OcrParser.parse("Some Name", Game.MTG, bottom)
        assertEquals("123", parsed.collectorNumber)
        assertEquals("dsk", parsed.setCode)
    }

    @Test
    fun prefersBottomStripOverNoisyBody() {
        val body = """
            Random 999 text
            50 something
        """.trimIndent()
        val bottom = "42 • MKM • EN"
        val parsed = OcrParser.parse(body, Game.MTG, bottom)
        assertEquals("42", parsed.collectorNumber)
        assertEquals("mkm", parsed.setCode)
    }

    @Test
    fun setCodeCandidatesIncludeOcrSwaps() {
        val variants = OcrParser.setCodeCandidates("0ft")
        assertTrue(variants.contains("0ft"))
        assertTrue(variants.contains("oft"))
    }

    @Test
    fun parsesPokemonCollectorFraction() {
        val text = """
            Charmander
            50 HP
            004/165
            MEW
        """.trimIndent()
        val parsed = OcrParser.parse(text, Game.POKEMON)
        assertEquals("Charmander", parsed.nameGuess)
        assertEquals("4", parsed.collectorNumber)
        assertNotNull(parsed.setCode)
    }

    @Test
    fun parsesItalianPokemonTeamRocketMuk() {
        val full = """
            Muk del Team Rocket
            Fase 1
            150 HP
        """.trimIndent()
        val bottom = "DRI 124/182"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottom)
        assertEquals("Muk del Team Rocket", parsed.nameGuess)
        assertEquals("124", parsed.collectorNumber)
        assertEquals("dri", parsed.setCode)
        assertEquals(182, parsed.setCardCount)
    }
}
