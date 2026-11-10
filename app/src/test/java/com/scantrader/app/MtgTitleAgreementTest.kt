package com.scantrader.app

import com.scantrader.app.scan.FrameNameGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtgTitleAgreementTest {
    @Test
    fun mirkwoodDoesNotConfirmMirkwoodMeditatorEitherWay() {
        assertFalse(FrameNameGate.mtgTitlesAgree("Mirkwood Meditator", "Mirkwood"))
        assertFalse(FrameNameGate.mtgTitlesAgree("Mirkwood", "Mirkwood Meditator"))
        assertFalse(FrameNameGate.mtgTitlesAgree("Mirkwood", "Mirkwood Meditator", allowTruncatedTitle = true))
        assertTrue(FrameNameGate.mtgTitlesAgree("Mirkwood", "Mirkwood"))
        assertTrue(FrameNameGate.mtgTitlesAgree("Mirkwood Meditator", "Mirkwood Meditator"))
    }

    @Test
    fun aCutWordStillAgreesAndACutTitleNeedsTheCollector() {
        assertTrue(FrameNameGate.mtgTitlesAgree("Squadra", "Squadr"))
        assertTrue(
            FrameNameGate.mtgTitlesAgree(
                "Winter, Giocatore di Squadra",
                "Winter, Giocatore di Squadr",
            ),
        )
        assertFalse(FrameNameGate.mtgTitlesAgree("Down, Down to Goblin-town", "Goblin-town"))
        assertTrue(
            FrameNameGate.mtgTitlesAgree(
                "Down, Down to Goblin-town",
                "Goblin-town",
                allowTruncatedTitle = true,
            ),
        )
        assertTrue(
            FrameNameGate.mtgTitlesAgree(
                "Down, Down to Goblin-town",
                "Goblir",
                allowTruncatedTitle = true,
            ),
        )
    }
}
