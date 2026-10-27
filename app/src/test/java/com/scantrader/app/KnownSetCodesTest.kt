package com.scantrader.app

import com.scantrader.app.data.catalog.KnownSetCodes
import com.scantrader.app.data.catalog.MtgSets
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.OcrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownSetCodesTest {
    @Test
    fun divFromDiventareIsDiscardedAndFraIsKept() {
        assertTrue(MtgSets.isKnown("fra"))
        assertTrue(MtgSets.isKnown("FRA"))
        assertFalse(MtgSets.isKnown("div"))
        assertFalse(MtgSets.isKnown("pru"))

        val bottom = """
            Se stesSs0 FHE
            dettaglio. Ora doveva solo farlo diventare realtà.
            U 0034 Momento chiave
            FRA ITMARA NAEL
        """.trimIndent()
        val parsed = OcrParser.parse(
            rawText = "Teoria Perfezionata",
            game = Game.MTG,
            bottomPriorityText = bottom,
            namePriorityText = "Teoria Perfezionata",
        )
        assertEquals("div", parsed.setCode)
        val scrubbed = KnownSetCodes.scrub(Game.MTG, parsed)
        assertNull(scrubbed.setCode)
        assertEquals("34", scrubbed.collectorNumber)
        assertEquals("Teoria Perfezionata", scrubbed.nameGuess)

        val kept = KnownSetCodes.scrub(
            Game.MTG,
            ParsedCardText("Echi Violenti", "95", "fra"),
        )
        assertEquals("fra", kept.setCode)
    }

    @Test
    fun pokemonKeepsRealIdsAndDropsGarbage() {
        assertTrue(KnownSetCodes.isKnown(Game.POKEMON, "dri"))
        assertTrue(KnownSetCodes.isKnown(Game.POKEMON, "sv2a"))
        assertTrue(KnownSetCodes.isKnown(Game.POKEMON, "oasc"))
        assertFalse(KnownSetCodes.isKnown(Game.POKEMON, "wird"))
        assertFalse(KnownSetCodes.isKnown(Game.POKEMON, "div"))
        val scrubbed = KnownSetCodes.scrub(
            Game.POKEMON,
            ParsedCardText("Machop", "52", "wird", 102),
        )
        assertNull(scrubbed.setCode)
        assertEquals(102, scrubbed.setCardCount)
    }
}
