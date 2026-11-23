package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.CardOrientation
import com.scantrader.app.scan.FrameNameGate
import com.scantrader.app.scan.OcrBitmapLease
import com.scantrader.app.scan.OcrParser
import com.scantrader.app.vision.MatchFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Image + set/number decide a Magic card. The OCR title is a tiebreak and never
 * vetoes them. Strings are from the Pixel live-scan.log of 20 Nov (You Happen On a Glade).
 */
class MtgImageFirstMatchTest {
    private val glade = IdentifiedCard(
        game = Game.MTG,
        name = "You Happen On a Glade",
        setCode = "afr",
        setName = "Adventures in the Forgotten Realms",
        collectorNumber = "214",
        scryfallId = "f9ae7693-a6b5-42f8-9f05-c0643b95a710",
        ocrScore = null,
        visualScore = 0.62755555f,
        galleryScore = 0.62755555f,
        matchConfidence = 0.62244374f,
        printedName = "Vi Imbattete in una Radura",
        printedLang = "it",
    )

    private val copyrightFrame = ParsedCardText(
        nameGuess = "H&C 2021 Wizard of the Coast",
        collectorNumber = "214",
        setCode = null,
    )

    @Test
    fun galleryAndCollectorKeepTheCardDespiteAConflictingOcrName() {
        assertTrue(FrameNameGate.agrees(glade, copyrightFrame))
        assertEquals(listOf(glade), FrameNameGate.keep(listOf(glade), copyrightFrame))
        val gate = MatchFusion.evaluateAutoMatch(glade, copyrightFrame, listOf(glade))
        assertTrue(gate.reason, gate.accepted)
        assertFalse(gate.nameConflict)
        assertEquals("cn+neural", gate.reason)
    }

    @Test
    fun englishNameOfADifferentCardDoesNotVetoGalleryAndCollector() {
        val frame = copyrightFrame.copy(nameGuess = "Ravening Warg")
        assertTrue(FrameNameGate.agrees(glade, frame))
        val gate = MatchFusion.evaluateAutoMatch(glade, frame, listOf(glade))
        assertTrue(gate.accepted)
        assertFalse(gate.nameConflict)
    }

    @Test
    fun strongImageAndCollectorAutosaveEvenWithAWrongTitle() {
        val strong = glade.copy(visualScore = 0.86f, galleryScore = 0.86f, matchConfidence = 0.80f)
        val gate = MatchFusion.evaluateAutoMatch(strong, copyrightFrame, listOf(strong))
        assertTrue(gate.accepted)
        assertFalse(gate.nameConflict)
        assertTrue(MatchFusion.allowsAutoSave(gate))
    }

    @Test
    fun weakGalleryAndCollectorStaysOnTheConfirmScreen() {
        val gate = MatchFusion.evaluateAutoMatch(glade, copyrightFrame, listOf(glade))
        assertFalse(MatchFusion.allowsAutoSave(gate))
    }

    @Test
    fun withoutImageAgreementTheNameStillCounts() {
        val catalogOnly = glade.copy(visualScore = null, galleryScore = null, ocrScore = 0.98f)
        val frame = copyrightFrame.copy(nameGuess = "Ravening Warg")
        assertFalse(FrameNameGate.agrees(catalogOnly, frame))
        // A fingerprint-ranker score on a catalog hit is not the gallery (Elrond case).
        val rankerOnly = glade.copy(visualScore = 0.80f, galleryScore = null, ocrScore = 0.98f)
        assertFalse(FrameNameGate.agrees(rankerOnly, frame))
        // Gallery hit on another collector: not this card.
        val otherCn = frame.copy(collectorNumber = "36")
        assertFalse(FrameNameGate.agrees(glade, otherCn))
        // A set read on the frame has to agree as well.
        val otherSet = frame.copy(setCode = "hob")
        assertFalse(FrameNameGate.agrees(glade, otherSet))
        assertTrue(FrameNameGate.agrees(glade, frame.copy(setCode = "afr")))
    }

    @Test
    fun weakImageAndNoNumberLetTheNameHelp() {
        val noNumber = copyrightFrame.copy(nameGuess = "You Happen On a Glade", collectorNumber = null)
        assertTrue(FrameNameGate.agrees(glade, noNumber))
        val wrongName = noNumber.copy(nameGuess = "Ravening Warg")
        assertFalse(FrameNameGate.agrees(glade, wrongName))
    }

    @Test
    fun copyrightTitleStripFromTheLogGivesNoName() {
        val strips = listOf(
            "M&C 2021 Wzards of the Coast\nfEHR\nhand.",
            "H&C 2021 Wizard of the Coast\nfLHR\nhand.",
            "TM&C 2021 Wizards of the Coast\nfEHR\nhand.",
            "M&C 2021 Wnds ofth Co",
            "AC 2021 Wids od the Coas\nr hand.",
        )
        for (strip in strips) {
            val parsed = OcrParser.parse(strip, Game.MTG, namePriorityText = strip)
            assertNull(strip, parsed.nameGuess)
        }
    }

    @Test
    fun creditAndCollectorLinesAreNeverTitles() {
        val lines = listOf(
            "M&C 2021 Wzards of the Coast",
            "™ & © 2021 Wizards of the Coast",
            "Wirards of the Coat",
            "Illus. Jarel Threat",
            "AFR • EN",
            "AFR EN ANNA FERR",
            "214/281 U",
            "1994",
        )
        for (line in lines) {
            assertTrue(line, CardOrientation.isCreditLine(line))
            assertTrue(line, CardOrientation.implausibleTitle(line))
            val parsed = OcrParser.parse(line, Game.MTG, namePriorityText = line)
            assertNull(line, parsed.nameGuess)
        }
        assertTrue(CardOrientation.implausibleTitle("fEHR"))
    }

    @Test
    fun realTitlesStillPass() {
        val titles = listOf(
            "You Happen On a Glade",
            "Vi Imbattete in una Radura",
            "Wizard's Lightning",
            "Coastal Piracy",
            "Lord of the Pit",
            "Tear // Wear",
        )
        for (title in titles) {
            assertFalse(title, CardOrientation.isCreditLine(title))
            assertFalse(title, CardOrientation.implausibleTitle(title))
        }
        val parsed = OcrParser.parse(
            "You Happen On a Glade",
            Game.MTG,
            namePriorityText = "You Happen On a Glade",
        )
        assertEquals("You Happen On a Glade", parsed.nameGuess)
    }

    @Test
    fun ocrBitmapIsReleasedOnlyAfterMlKitFinishes() {
        var released = 0
        val lease = OcrBitmapLease { released++ }
        lease.begin()
        lease.begin()
        // Coroutine cancelled: the caller is done, ML Kit is still reading.
        lease.close()
        assertEquals(0, released)
        lease.end()
        assertEquals(0, released)
        lease.end()
        assertEquals(1, released)
        lease.end()
        lease.close()
        assertEquals(1, released)
    }

    @Test
    fun ocrBitmapIsReleasedOnCloseWhenNothingIsInFlight() {
        var released = 0
        val lease = OcrBitmapLease { released++ }
        lease.begin()
        lease.end()
        assertEquals(0, released)
        lease.close()
        assertEquals(1, released)
    }
}
