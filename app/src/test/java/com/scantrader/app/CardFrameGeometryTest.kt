package com.scantrader.app

import com.scantrader.app.scan.CardFrameGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class CardFrameGeometryTest {
    @Test
    fun frameKeepsCardAspectOnATallPreview() {
        val viewW = 1080f
        val viewH = 2400f
        val rect = CardFrameGeometry.frameIn(viewW, viewH)
        val expectedW = viewW * CardFrameGeometry.WIDTH_FRACTION
        val expectedH = min(
            expectedW / CardFrameGeometry.ASPECT,
            viewH * CardFrameGeometry.MAX_HEIGHT_FRACTION,
        )
        assertEquals(expectedW, rect.width, 0.01f)
        assertEquals(expectedH, rect.height, 0.01f)
        assertEquals(expectedW / CardFrameGeometry.ASPECT, rect.height, 0.01f)
        assertEquals((viewW - expectedW) / 2f, rect.left, 0.01f)
    }

    @Test
    fun frameCapsHeightOnAShortPreview() {
        val rect = CardFrameGeometry.frameIn(1000f, 500f)
        assertEquals(500f * CardFrameGeometry.MAX_HEIGHT_FRACTION, rect.height, 0.01f)
    }

    @Test
    fun titleStripExcludesTheRightSideManaOrHp() {
        assertTrue(CardFrameGeometry.MTG_TITLE_WIDTH_FRACTION < 1f)
        assertTrue(CardFrameGeometry.POKEMON_TITLE_WIDTH_FRACTION < 1f)
        assertTrue(
            CardFrameGeometry.MTG_TITLE_WIDTH_FRACTION < CardFrameGeometry.POKEMON_TITLE_WIDTH_FRACTION,
        )
    }

    @Test
    fun repeatedFramesMatchSoTheCropStaysOnTheGuide() {
        val first = CardFrameGeometry.frameIn(1080f, 2400f)
        val second = CardFrameGeometry.frameIn(1080f, 2400f)
        assertEquals(first, second)
    }

    @Test
    fun lockedViewportKeepsTheGuideWhenThePreviewBoxChangesAspect() {
        val lockedW = 1080f
        val lockedH = 2400f
        val before = CardFrameGeometry.frameStable(lockedW, lockedH, lockedW, lockedH)
        assertEquals(CardFrameGeometry.frameIn(lockedW, lockedH), before)
        assertEquals(CardFrameGeometry.ASPECT, before.width / before.height, 0.01f)

        // A rebind that shortens the view would height-cap a freshly computed guide.
        val recomputed = CardFrameGeometry.frameIn(1080f, 1800f)
        assertTrue(kotlin.math.abs(recomputed.width / recomputed.height - CardFrameGeometry.ASPECT) > 0.02f)

        val after = CardFrameGeometry.frameStable(
            viewWidth = 1080f,
            viewHeight = 1800f,
            lockedWidth = lockedW,
            lockedHeight = lockedH,
        )
        assertEquals(CardFrameGeometry.ASPECT, after.width / after.height, 0.01f)
        assertEquals(before.width / before.height, after.width / after.height, 0.001f)
        val again = CardFrameGeometry.frameStable(1080f, 1800f, lockedW, lockedH)
        assertEquals(after, again)
    }

    @Test
    fun trainerStripsUseTheTitleRulesAndBottomCorner() {
        assertTrue(CardFrameGeometry.TRAINER_TITLE_HEIGHT_FRACTION > 0.22f)
        assertTrue(CardFrameGeometry.TRAINER_TITLE_WIDTH_FRACTION > CardFrameGeometry.POKEMON_TITLE_WIDTH_FRACTION)
        assertTrue(CardFrameGeometry.TRAINER_RULES_TOP_FRACTION > CardFrameGeometry.TRAINER_TITLE_HEIGHT_FRACTION)
        assertTrue(CardFrameGeometry.TRAINER_RULES_BOTTOM_FRACTION < 0.9f)
        assertTrue(CardFrameGeometry.TRAINER_BOTTOM_HEIGHT_FRACTION < 0.24f)
        assertTrue(CardFrameGeometry.TRAINER_BOTTOM_WIDTH_FRACTION > 0.42f)
    }
}
