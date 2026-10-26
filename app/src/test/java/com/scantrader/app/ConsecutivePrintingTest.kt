package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.scan.CardFinish
import com.scantrader.app.scan.ConsecutivePrintingGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsecutivePrintingTest {
    private fun card(
        name: String,
        set: String,
        cn: String,
        lang: String?,
        finish: String? = null,
        game: Game = Game.MTG,
    ) = IdentifiedCard(
        game = game,
        name = name,
        setCode = set,
        setName = set,
        collectorNumber = cn,
        scryfallId = "$set-$cn-$lang",
        catalogLang = lang,
        finish = finish,
    )

    @Test
    fun samePrintingIsBlockedUntilADifferentOneIsAccepted() {
        val guard = ConsecutivePrintingGuard()
        val echi = card("Echi Violenti", "fra", "95", "it")
        val again = echi.copy(collectorNumber = "095", scryfallId = "other-id")
        val teoria = card("Teoria Perfezionata", "fra", "34", "it")
        assertFalse(guard.sameAsLast(echi))
        guard.noteAccepted(echi)
        assertTrue(guard.sameAsLast(again))
        assertFalse(guard.sameAsLast(teoria))
        guard.noteAccepted(teoria)
        assertFalse(guard.sameAsLast(echi))
        assertTrue(guard.sameAsLast(teoria))
    }

    @Test
    fun differentLanguageOrFinishIsAllowed() {
        val guard = ConsecutivePrintingGuard()
        val italian = card("Echi Violenti", "fra", "95", "it")
        guard.noteAccepted(italian)
        assertFalse(guard.sameAsLast(italian.copy(catalogLang = "en", printedLang = null)))
        assertFalse(
            guard.sameAsLast(
                italian.copy(finish = CardFinish.from(listOf("etched"), listOf("showcase"), null)),
            ),
        )
        assertFalse(guard.sameAsLast(italian.copy(game = Game.POKEMON, tcgdexId = "x")))
        assertFalse(guard.sameAsLast(italian.copy(catalogLang = null, printedLang = null)))
    }

    @Test
    fun finishTokenKeepsEtchedApartFromTheNormalPrinting() {
        assertEquals(
            "foil+nonfoil",
            CardFinish.from(listOf("foil", "nonfoil"), null, null),
        )
        assertEquals(
            "etched|frame:showcase",
            CardFinish.from(listOf("etched"), listOf("showcase"), null),
        )
        val guard = ConsecutivePrintingGuard()
        val normal = card("Echi Violenti", "fra", "95", "it", finish = "nonfoil+foil")
        val etched = card("Echi Violenti", "fra", "95", "it", finish = "etched")
        guard.noteAccepted(normal)
        assertFalse(guard.sameAsLast(etched))
        assertTrue(guard.sameAsLast(normal.copy(name = "Violent Echo")))
    }
}
