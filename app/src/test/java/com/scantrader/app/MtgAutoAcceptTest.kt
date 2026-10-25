package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.RejectedSetCodes
import com.scantrader.app.scan.SetPrintingGuard
import com.scantrader.app.scan.StableTopAccept
import com.scantrader.app.vision.MatchFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtgAutoAcceptTest {
    private val italian = ParsedCardText(
        nameGuess = "Serpente di Bosco Dorato",
        collectorNumber = null,
        setCode = "pru",
    )

    private val hind = IdentifiedCard(
        game = Game.MTG,
        name = "Golden Hind",
        setCode = "bng",
        setName = "Born of the Gods",
        collectorNumber = "141",
        scryfallId = "bng-141",
        ocrScore = 0.40f,
        visualScore = 0.73f,
        matchConfidence = 0.60f,
        printedName = "Serpente di Bosco Dorato",
        printedLang = "it",
    )

    @Test
    fun exactLocalizedNameAndSingleGalleryPrintingAcceptsWithoutCollector() {
        val held = MatchFusion.evaluateAutoMatch(hind, italian)
        assertFalse(held.accepted)
        assertEquals("softFail", held.reason)
        assertTrue(held.nameOk)
        assertFalse(held.cnOk)
        assertFalse(held.setOk)

        val decision = MatchFusion.evaluateAutoMatch(hind, italian, listOf(hind))
        assertTrue(decision.accepted)
        assertEquals("name+gallery", decision.reason)
        assertTrue(decision.nameOk)
        assertFalse(decision.nameConflict)
        assertFalse(decision.cnOk)
        assertFalse(decision.setOk)
        assertFalse(decision.strict)
        assertFalse(MatchFusion.allowsAutoSave(decision))
        assertEquals(
            "il nome coincide e questa è l'unica stampa",
            MatchFusion.signalPhrase(decision.reason),
        )

        val wrongNumber = italian.copy(collectorNumber = "14")
        val still = MatchFusion.evaluateAutoMatch(hind, wrongNumber, listOf(hind))
        assertTrue(still.accepted)
        assertEquals("name+gallery", still.reason)
        assertFalse(still.cnOk)
    }

    @Test
    fun twoPrintingsOfTheSameNameDoNotTakeTheGalleryShortcut() {
        val other = hind.copy(setCode = "ths", collectorNumber = "140", scryfallId = "ths-140")
        val decision = MatchFusion.evaluateAutoMatch(hind, italian, listOf(hind, other))
        assertFalse(decision.accepted)
        assertEquals("softFail", decision.reason)
        assertFalse(MatchFusion.allowsAutoSave(decision))
    }

    @Test
    fun elrondIsStillANameConflictWhenItIsTheOnlyCandidate() {
        val parsed = ParsedCardText(
            nameGuess = "Ravening Warg",
            collectorNumber = "36",
            setCode = "hob",
        )
        val elrond = IdentifiedCard(
            game = Game.MTG,
            name = "Elrond, Moon-Reader",
            setCode = "hob",
            setName = "The Hobbit",
            collectorNumber = "36",
            scryfallId = "elrond",
            ocrScore = 0.98f,
            visualScore = 0.80f,
            matchConfidence = 0.90f,
        )
        val decision = MatchFusion.evaluateAutoMatch(elrond, parsed, listOf(elrond))
        assertFalse(decision.accepted)
        assertTrue(decision.nameConflict)
        assertFalse(decision.nameOk)
        assertFalse(MatchFusion.allowsAutoSave(decision))

        val streak = StableTopAccept()
        repeat(3) {
            assertFalse(streak.ready(elrond, decision))
        }
    }

    @Test
    fun threeSteadyFramesAcceptAtMediumConfidence() {
        val held = MatchFusion.evaluateAutoMatch(hind, italian)
        assertFalse(held.accepted)
        assertTrue(held.nameOk)
        assertFalse(held.nameConflict)
        val streak = StableTopAccept()
        assertFalse(streak.ready(hind, held))
        assertFalse(streak.ready(hind, held))
        assertTrue(streak.ready(hind, held))
        assertEquals(
            "la stessa carta resta inquadrata per più letture",
            MatchFusion.signalPhrase("stableTop"),
        )

        val other = StableTopAccept()
        other.ready(hind, held)
        other.ready(hind, held)
        assertFalse(other.ready(hind.copy(collectorNumber = "10"), held))
    }

    @Test
    fun setThatIsNotAPrintingOfTheNameIsDiscarded() {
        assertEquals("pru", SetPrintingGuard.unknownSet(italian, listOf(hind)))
        assertNull(SetPrintingGuard.discardUnknownSet(italian, listOf(hind)).setCode)
        val kept = SetPrintingGuard.discardUnknownSet(italian.copy(setCode = "bng"), listOf(hind))
        assertEquals("bng", kept.setCode)

        val warg = ParsedCardText(nameGuess = "Ravening Warg", collectorNumber = "36", setCode = "hob")
        val elrond = IdentifiedCard(
            game = Game.MTG,
            name = "Elrond, Moon-Reader",
            setCode = "hob",
            setName = "The Hobbit",
            collectorNumber = "36",
            ocrScore = 0.98f,
            matchConfidence = 0.98f,
        )
        assertEquals("hob", SetPrintingGuard.discardUnknownSet(warg, listOf(elrond)).setCode)

        val clean = italian.copy(setCode = null)
        assertTrue(
            MatchFusion.ocrAgreement(hind, clean) > MatchFusion.ocrAgreement(hind, italian),
        )

        val rejected = RejectedSetCodes()
        rejected.note(italian, listOf(hind))
        assertNull(rejected.scrub(italian).setCode)
        val nextName = italian.copy(nameGuess = "Ravening Warg", setCode = "hob")
        assertEquals("hob", rejected.scrub(nextName).setCode)
    }
}
