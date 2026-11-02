package com.scantrader.app

import com.scantrader.app.data.model.CardLanguage
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
}
