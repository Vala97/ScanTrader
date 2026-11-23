package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.scan.CardOrientation
import com.scantrader.app.scan.OcrParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strings are copied from the Pixel live capture of the upside-down
 * `You Happen On a Glade` scan (2026-11-20, live-scan.log).
 */
class MtgTitleCreditFilterTest {
    private val copyrightReads = listOf(
        "M&C 2021 Wnds ofth Co",
        "M&C 2021 Wzards of the Coast",
        "M&C 2021 Wuardh of the Coast",
        "H&C 2021 Wizard of the Coast",
        "H&C 202i Wirards of the Coat",
        "TM&C 2021 Wizards of the Coast",
        "w&C 2021 Wiards of the Coast",
        "AC 2021 Wids od the Coas",
        "Mac 2021 Wnds f the C",
        "™ & © 1993-2024 Wizards of the Coast",
    )

    @Test
    fun everyCopyrightReadFromTheGladeLogIsACreditLine() {
        for (line in copyrightReads) {
            assertTrue(line, CardOrientation.isCreditLine(line))
            assertTrue(line, CardOrientation.implausibleTitle(line))
        }
    }

    @Test
    fun collectorAndSetLanguageLinesAreCreditLines() {
        for (line in listOf("214/281 U", "214/281U", "AFR EN", "AFR -EN", "AFR • EN", "Illus. Someone", "214 U")) {
            assertTrue(line, CardOrientation.isCreditLine(line))
        }
    }

    @Test
    fun lowercaseOcrCrumbsAreNotTitles() {
        assertTrue(CardOrientation.implausibleTitle("fEHR"))
        assertTrue(CardOrientation.implausibleTitle("fLHR"))
        assertTrue(CardOrientation.implausibleTitle("feHR"))
    }

    @Test
    fun realTitlesStayTitles() {
        val titles = listOf(
            "You Happen On a Glade",
            "Happen On a Glade",
            "Glade",
            "Ravening Warg",
            "Elrond, Moon-Reader",
            "Mind Stone",
            "Tear // Wear",
            "Stormcarved Coast",
            "Vi Imbattete in una Radura",
        )
        for (title in titles) {
            assertFalse(title, CardOrientation.isCreditLine(title))
            assertFalse(title, CardOrientation.implausibleTitle(title))
        }
    }

    @Test
    fun upsideDownGladeTitleStripNeverYieldsTheCopyright() {
        val strips = listOf(
            "M&C 2021 Wzards of the Coast\nfEHR\nhand.",
            "H&C 2021 Wizard of the Coast\nfLHR\nhand.",
            "TM&C 2021 Wizards of the Coast\nfEHR\nhand.",
            "M&C 2021 Wnds ofth Co",
            "AC 2021 Wids od the Coas\nr hand.",
        )
        val full = "H&C 202i Wirards of the Coat\nANNs FHR\nto your hand.\n" +
            "permanent card from your graveyard\n• Make Camp Return target\nAFR EN\n214/281 U\n" +
            "shufie.\nthem, put them into your hand, then"
        for (strip in strips) {
            val parsed = OcrParser.parse(full, Game.MTG, namePriorityText = strip)
            val name = parsed.nameGuess
            assertFalse("$strip -> $name", name != null && CardOrientation.isCreditLine(name))
            assertFalse("$strip -> $name", name.orEmpty().contains("Coast", ignoreCase = true))
            // The strip is the bottom of the card: the 180° reread must fire.
            assertTrue(strip, CardOrientation.regionMappingOff(strip, name))
        }
    }

    @Test
    fun uprightGladeKeepsItsTitle() {
        val full = "You Happen On a Glade\nChoose one —\n• Make Camp — Return target permanent card " +
            "from your graveyard to your hand.\n214/281 U\nAFR • EN\n™ & © 2021 Wizards of the Coast"
        val parsed = OcrParser.parse(full, Game.MTG, namePriorityText = "You Happen On a Glade")
        assertEquals("You Happen On a Glade", parsed.nameGuess)
        assertFalse(CardOrientation.regionMappingOff("You Happen On a Glade", parsed.nameGuess))
    }

    @Test
    fun rotatedRereadWinsOverTheCopyright() {
        assertTrue(CardOrientation.rotatedPassWins("H&C 2021 Wizard of the Coast", "You Happen On a Glade"))
        assertTrue(CardOrientation.rotatedPassWins("fEHR", "You Happen On a Glade"))
        assertNotEquals(true, CardOrientation.rotatedPassWins("You Happen On a Glade", "M&C 2021 Wzards of the Coast"))
    }
}