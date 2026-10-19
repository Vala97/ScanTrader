package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.vision.MatchFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchFusionTest {
    private val parsed = ParsedCardText(
        nameGuess = "Lightning Bolt",
        collectorNumber = "161",
        setCode = "m21",
    )

    @Test
    fun ranksVisualWinnerAboveWeakOcrHit() {
        val wrongSet = card("Wrong Print", "znr", "161", ocr = 0.98f, visual = 0.30f)
        val rightArt = card("Lightning Bolt", "m21", "161", ocr = 0.70f, visual = 0.92f)
        val ranked = MatchFusion.fuse(listOf(wrongSet, rightArt), parsed)
        assertEquals("Lightning Bolt", ranked.first().name)
        assertEquals("m21", ranked.first().setCode)
        assertTrue((ranked.first().matchConfidence ?: 0f) > (ranked.last().matchConfidence ?: 0f))
    }

    @Test
    fun autoMatchRequiresStrongEvidence() {
        val weak = card("Maybe", "m21", "161", ocr = 0.50f, visual = 0.55f)
        assertFalse(MatchFusion.isConfidentAutoMatch(MatchFusion.score(weak, parsed)))

        val strong = card("Lightning Bolt", "m21", "161", ocr = 0.90f, visual = 0.88f)
        assertTrue(MatchFusion.isConfidentAutoMatch(MatchFusion.score(strong, parsed)))
    }

    @Test
    fun exactOcrWithoutVisualCanAutoMatch() {
        val exact = card("Lightning Bolt", "m21", "161", ocr = 0.98f, visual = null)
        val scored = MatchFusion.score(exact, parsed)
        assertTrue(MatchFusion.isConfidentAutoMatch(scored))
    }

    private fun card(
        name: String,
        set: String,
        number: String,
        ocr: Float?,
        visual: Float?,
    ) = IdentifiedCard(
        game = Game.MTG,
        name = name,
        setCode = set,
        setName = set.uppercase(),
        collectorNumber = number,
        ocrScore = ocr,
        visualScore = visual,
    )
}
