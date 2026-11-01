package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.CjkSkip
import com.scantrader.app.vision.OnnxProviders
import com.scantrader.app.vision.RotationSkip
import com.scantrader.app.vision.VisualRankerSkip
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotSkipTest {
    @Test
    fun skipsHalfTurnWhenCosineAndMarginAreClear() {
        assertTrue(RotationSkip.skip(listOf(0.94f, 0.80f)))
        assertTrue(RotationSkip.skip(listOf(RotationSkip.MIN_BEST_COSINE, 0.80f)))
        assertTrue(RotationSkip.skip(listOf(Float.NaN, 0.99f)))
    }

    @Test
    fun keepsHalfTurnWhenTheUprightHitIsOnlyGood() {
        assertFalse(RotationSkip.skip(listOf(0.89f, 0.40f)))
        assertFalse(RotationSkip.skip(listOf(0.96f, 0.90f)))
        assertFalse(RotationSkip.skip(emptyList()))
        assertFalse(RotationSkip.skip(listOf(Float.NaN)))
    }

    @Test
    fun skipsHalfTurnWhenTheCatalogHasASingleStrongHit() {
        assertTrue(RotationSkip.skip(listOf(0.95f)))
        assertFalse(RotationSkip.skip(listOf(0.80f)))
    }

    @Test
    fun sortsCosinesBeforeTheMarginCheck() {
        assertTrue(RotationSkip.skip(listOf(0.70f, 0.96f)))
    }

    @Test
    fun skipsRankerWhenTitleSetAndNumberAgreeOnOneRow() {
        val parsed = ParsedCardText("Lightning Bolt", "161", "m21")
        val bolt = card("Lightning Bolt", "m21", "161")
        val other = card("Shock", "m21", "159")
        assertTrue(VisualRankerSkip.skip(parsed, listOf(bolt, other)))
    }

    @Test
    fun keepsRankerWhenOnlyTheCollectorNumberIsKnown() {
        val parsed = ParsedCardText(nameGuess = null, collectorNumber = "161", setCode = "m21")
        assertFalse(VisualRankerSkip.skip(parsed, listOf(card("Lightning Bolt", "m21", "161"))))
        val shortTitle = ParsedCardText("LB", "161", "m21")
        assertFalse(VisualRankerSkip.skip(shortTitle, listOf(card("Lightning Bolt", "m21", "161"))))
    }

    @Test
    fun keepsRankerWhenTheSetOrTheTitleDisagrees() {
        val parsed = ParsedCardText("Lightning Bolt", "161", "m21")
        assertFalse(VisualRankerSkip.skip(parsed, listOf(card("Lightning Bolt", "znr", "161"))))
        assertFalse(VisualRankerSkip.skip(parsed, listOf(card("Shock", "m21", "161"))))
    }

    @Test
    fun keepsRankerWhenTwoRowsAgree() {
        val parsed = ParsedCardText("Lightning Bolt", "161", "m21")
        val rows = listOf(
            card("Lightning Bolt", "m21", "161"),
            card("Lightning Bolt", "m21", "0161"),
        )
        assertFalse(VisualRankerSkip.skip(parsed, rows))
    }

    @Test
    fun keepsRankerWhenALongSetCodeDoesNotMatch() {
        val parsed = ParsedCardText("Pikachu", "25", "sv3pt5")
        val wrongSet = card("Pikachu", "base1", "25", Game.POKEMON)
        assertFalse(VisualRankerSkip.skip(parsed, listOf(wrongSet)))
    }

    @Test
    fun skipsRankerForAPokemonRowThatMatchesAllThree() {
        val parsed = ParsedCardText("Pikachu", "25", "base1")
        val pika = card("Pikachu", "base1", "25", Game.POKEMON)
        val other = card("Raichu", "base1", "14", Game.POKEMON)
        assertTrue(VisualRankerSkip.skip(parsed, listOf(other, pika)))
    }

    @Test
    fun nnapiStaysOffOnOldApisAndEmulators() {
        assertFalse(OnnxProviders.nnapiIsSafe(28, emulator = false))
        assertFalse(OnnxProviders.nnapiIsSafe(34, emulator = true))
        assertTrue(OnnxProviders.nnapiIsSafe(OnnxProviders.MIN_NNAPI_SDK, emulator = false))
        assertTrue(
            OnnxProviders.emulator(
                fingerprint = "generic/sdk_gphone64_arm64/emu64a",
                hardware = "ranchu",
                model = "sdk_gphone64_arm64",
                product = "sdk_gphone64_arm64",
            ),
        )
        assertFalse(
            OnnxProviders.emulator(
                fingerprint = "google/husky/husky:14/AP2A.240805.005/12025142:user/release-keys",
                hardware = "husky",
                model = "Pixel 8 Pro",
                product = "husky",
            ),
        )
    }

    @Test
    fun latinOcrSkipsCjkOnlyWithATitleLineSetAndNumber() {
        assertTrue(
            CjkSkip.latinCoversTitleSetAndNumber(
                "Pikachu\n25/102\nsv3",
            ),
        )
        assertFalse(CjkSkip.latinCoversTitleSetAndNumber("25/102\nsv3"))
        assertFalse(
            CjkSkip.latinCoversTitleSetAndNumber(
                "Draw three cards\n25/102\nsv3",
            ),
        )
        assertFalse(CjkSkip.latinCoversTitleSetAndNumber("Pikachu\nsv3"))
    }

    private fun card(
        name: String,
        set: String,
        number: String,
        game: Game = Game.MTG,
    ) = IdentifiedCard(
        game = game,
        name = name,
        setCode = set,
        setName = set,
        collectorNumber = number,
    )
}
