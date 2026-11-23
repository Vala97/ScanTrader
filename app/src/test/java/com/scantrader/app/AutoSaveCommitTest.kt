package com.scantrader.app

import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.ui.confirm.AutoSaveCommit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSaveCommitTest {
    @Test
    fun timeoutStillSavesWhenTheListWasCleared() {
        assertTrue(
            AutoSaveCommit.shouldCommit(
                cancelled = false,
                saving = false,
                targetPresent = true,
                scheduledKey = "lrw|174",
                selectedKey = null,
                userPickedOther = false,
            ),
        )
    }

    @Test
    fun cancelAndADifferentCardDoNotSave() {
        assertFalse(
            AutoSaveCommit.shouldCommit(
                cancelled = true,
                saving = false,
                targetPresent = true,
                scheduledKey = "lrw|174",
                selectedKey = null,
                userPickedOther = false,
            ),
        )
        assertFalse(
            AutoSaveCommit.shouldCommit(
                cancelled = false,
                saving = false,
                targetPresent = true,
                scheduledKey = "lrw|174",
                selectedKey = "mor|98",
                userPickedOther = false,
            ),
        )
        assertFalse(
            AutoSaveCommit.shouldCommit(
                cancelled = false,
                saving = false,
                targetPresent = true,
                scheduledKey = "lrw|174",
                selectedKey = null,
                userPickedOther = true,
            ),
        )
    }

    @Test
    fun timeoutKeepsTheLastSavedLanguage() {
        assertEquals(
            CardLanguage.ITALIAN,
            AutoSaveCommit.languageOnTimeout(
                lastSaved = CardLanguage.ITALIAN,
                session = CardLanguage.SPANISH,
                fallback = CardLanguage.ENGLISH,
                spanishClue = false,
            ),
        )
        assertFalse(AutoSaveCommit.spanishClue("Volare", "LRW • ES 80"))
        assertEquals(
            CardLanguage.ITALIAN,
            AutoSaveCommit.languageOnTimeout(
                lastSaved = CardLanguage.ITALIAN,
                session = CardLanguage.SPANISH,
                fallback = CardLanguage.ENGLISH,
                spanishClue = AutoSaveCommit.spanishClue("Volare", "LRW • ES"),
            ),
        )
    }

    @Test
    fun timeoutChoosesSpanishOnlyWithARealClue() {
        assertTrue(AutoSaveCommit.spanishClue("Señor de la Atlántida", null))
        assertTrue(AutoSaveCommit.spanishClue(null, "debilidad fuego"))
        assertFalse(AutoSaveCommit.spanishClue("Bolla Protettiva", "lrw 80"))
        assertEquals(
            CardLanguage.SPANISH,
            AutoSaveCommit.languageOnTimeout(
                lastSaved = CardLanguage.ITALIAN,
                session = CardLanguage.ITALIAN,
                fallback = CardLanguage.ENGLISH,
                spanishClue = true,
            ),
        )
        assertEquals(
            CardLanguage.ENGLISH,
            AutoSaveCommit.languageOnTimeout(
                lastSaved = null,
                session = null,
                fallback = CardLanguage.ENGLISH,
                spanishClue = false,
            ),
        )
    }

    @Test
    fun timeoutUsesTheBestLanguageOnHand() {
        assertEquals(
            CardLanguage.ITALIAN,
            AutoSaveCommit.languageAfterWait(
                detected = null,
                local = null,
                printed = CardLanguage.ITALIAN,
                session = CardLanguage.ENGLISH,
                fallback = CardLanguage.ENGLISH,
            ),
        )
        assertEquals(
            CardLanguage.ENGLISH,
            AutoSaveCommit.languageAfterWait(
                detected = null,
                local = null,
                printed = null,
                session = null,
                fallback = CardLanguage.ENGLISH,
            ),
        )
    }

    @Test
    fun unrecognizedMagicLanguageBlocksAutoSaveOnlyForMagic() {
        assertTrue(AutoSaveCommit.languageBlocksAutoSave(Game.MTG, detectEnabled = true, recognized = false))
        assertFalse(AutoSaveCommit.languageBlocksAutoSave(Game.MTG, detectEnabled = true, recognized = true))
        // Detection switched off in settings: the old behaviour.
        assertFalse(AutoSaveCommit.languageBlocksAutoSave(Game.MTG, detectEnabled = false, recognized = false))
        // Pokémon and the other games are unchanged.
        assertFalse(AutoSaveCommit.languageBlocksAutoSave(Game.POKEMON, detectEnabled = true, recognized = false))
    }

    @Test
    fun theProposalIsThePreviousCardsLanguage() {
        assertEquals(
            CardLanguage.SPANISH,
            AutoSaveCommit.previousCardLanguage(CardLanguage.SPANISH, CardLanguage.ITALIAN, CardLanguage.ENGLISH),
        )
        assertEquals(
            CardLanguage.ITALIAN,
            AutoSaveCommit.previousCardLanguage(null, CardLanguage.ITALIAN, CardLanguage.ENGLISH),
        )
        assertEquals(CardLanguage.ENGLISH, AutoSaveCommit.previousCardLanguage(null, null, CardLanguage.ENGLISH))
    }
}
