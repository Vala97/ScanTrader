package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.ScanSession
import com.scantrader.app.vision.MatchFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSessionTest {
    @Test
    fun newSessionAsksForTheGame() {
        val session = ScanSession()
        assertFalse(session.gameChosen)
        assertFalse(session.autoScan)
    }

    @Test
    fun resetResultsKeepsGameAndRearmsNextCard() {
        val session = ScanSession()
        session.game = Game.POKEMON
        session.gameChosen = true
        session.autoScan = true
        session.torchOn = true
        session.lensKey = "zoom:0.5"
        session.ocrText = "Pikachu"
        session.parsed = ParsedCardText("Pikachu", "58", "base1")
        session.candidates = listOf(
            IdentifiedCard(
                game = Game.POKEMON,
                name = "Pikachu",
                setCode = "base1",
                setName = "Base",
                collectorNumber = "58",
            ),
        )
        session.autoMatch = MatchFusion.AutoMatchDecision(
            accepted = true,
            strict = true,
            reason = "strict",
            confidence = 0.9f,
        )
        session.autoMatchCardKey = "base1-58"
        session.errorMessage = "vecchio errore"

        session.resetResults()

        assertEquals(Game.POKEMON, session.game)
        assertTrue(session.gameChosen)
        assertTrue(session.autoScan)
        assertTrue(session.torchOn)
        assertEquals("zoom:0.5", session.lensKey)
        assertEquals("", session.ocrText)
        assertNull(session.parsed)
        assertTrue(session.candidates.isEmpty())
        assertNull(session.autoMatch)
        assertNull(session.autoMatchCardKey)
        assertNull(session.errorMessage)
    }
}
