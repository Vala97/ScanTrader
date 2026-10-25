package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.AcceptedCandidateLock
import com.scantrader.app.scan.AutosaveDuplicateGuard
import com.scantrader.app.scan.FrameNameGate
import com.scantrader.app.scan.FrameQuery
import com.scantrader.app.scan.SpeculativeCatalog
import com.scantrader.app.ui.confirm.AutoSaveSchedule
import com.scantrader.app.vision.MatchFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * scan_log10: Abra (base1-43) was auto-saved, then a Machop frame reused that
 * result and saved Abra again. The handoff must yield Machop.
 */
class AutoScanHandoffTest {
    @Test
    fun abraThenMachopYieldsMachop() {
        val lock = AcceptedCandidateLock()
        val guard = AutosaveDuplicateGuard()
        val speculative = SpeculativeCatalog()

        lock.lock("base1-43", "43", "Abra")
        guard.onOverlayClosed(lock.key, lock.snapshot?.name)
        lock.clear()
        speculative.clear()
        assertNull(lock.key)

        val machop = ParsedCardText("Machop", "52", "base", 102)
        assertFalse(lock.shouldIgnore(machop))
        guard.observe(machop)

        val votedAbra = ParsedCardText("Abra", "43", "base", 102)
        val query = FrameQuery.resolve(machop, votedAbra)
        assertEquals("Machop", query.nameGuess)
        assertEquals("52", query.collectorNumber)

        val stale = listOf(pokemon("Abra", "base1-43", "43"))
        assertTrue(FrameNameGate.keep(stale, machop).isEmpty())
        val rejected = MatchFusion.evaluateAutoMatch(stale.first(), machop)
        assertFalse(rejected.accepted)
        assertFalse(MatchFusion.allowsAutoSave(rejected))

        val bare = ParsedCardText("Machop", null, null, 102)
        val bareQuery = FrameQuery.resolve(bare, votedAbra)
        assertEquals("Machop", bareQuery.nameGuess)
        assertNull(bareQuery.collectorNumber)
        assertTrue(FrameNameGate.keep(stale, bare).isEmpty())

        val started = speculative.onOcr(ParsedCardText("Abra", "43", "base", 102))
        assertTrue(started.start)
        val dropped = speculative.onOcr(bare)
        assertTrue(dropped.dropInFlight)
        assertFalse(dropped.accept)

        val machopCard = pokemon("Machop", "base1-52", "52")
        val kept = FrameNameGate.keep(listOf(machopCard, stale.first()), machop)
        assertEquals("Machop", kept.first().name)
        assertEquals(1, kept.size)
        val accepted = MatchFusion.evaluateAutoMatch(kept.first(), query)
        assertTrue(accepted.accepted)
        assertEquals("Machop", kept.first().name)
        assertTrue(guard.allowAutoSave(machopCard.identityKey()))
        assertTrue(guard.allowAutoSave("base1-43"))
    }

    @Test
    fun sameCardIsNotAutoSavedAgainUntilTheSceneOrNameChanges() {
        val guard = AutosaveDuplicateGuard()
        guard.onOverlayClosed("base1-43", "Abra")
        guard.observe(ParsedCardText("Abra", "43", "base", 102))
        assertFalse(guard.allowAutoSave("base1-43"))
        assertTrue(guard.holdingSameCard())

        guard.observe(ParsedCardText("Machop", "52", "base", 102))
        assertFalse(guard.holdingSameCard())
        assertTrue(guard.allowAutoSave("base1-52"))

        val again = AutosaveDuplicateGuard()
        again.onOverlayClosed("base1-43", "Abra")
        again.noteSceneChanged()
        assertTrue(again.allowAutoSave("base1-43"))
    }

    @Test
    fun majorityNameSurvivesWhenTheFrameStillAgrees() {
        val frame = ParsedCardText("Kilowattrel", "72", null, 217)
        val voted = ParsedCardText("Kilowattrel di Kissara", "72", "asc", 217)
        val query = FrameQuery.resolve(frame, voted)
        assertEquals("Kilowattrel di Kissara", query.nameGuess)
        assertEquals("72", query.collectorNumber)
    }

    @Test
    fun autosaveSchedulesOncePerMatch() {
        val first = AutoSaveSchedule.claim(null, "base1-43")
        assertTrue(first.start)
        val again = AutoSaveSchedule.claim(first.activeKey, "base1-43")
        assertFalse(again.start)
        assertEquals("base1-43", again.activeKey)
        val machop = AutoSaveSchedule.claim(again.activeKey, "base1-52")
        assertTrue(machop.start)
        assertEquals("base1-52", machop.activeKey)
    }

    private fun pokemon(name: String, id: String, cn: String) = IdentifiedCard(
        game = Game.POKEMON,
        name = name,
        setCode = "base1",
        setName = "Base",
        collectorNumber = cn,
        tcgdexId = id,
        ocrScore = 1f,
        visualScore = null,
        matchConfidence = 1f,
    )
}
