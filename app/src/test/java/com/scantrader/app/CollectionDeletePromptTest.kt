package com.scantrader.app

import com.scantrader.app.ui.home.CollectionDeletePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionDeletePromptTest {
    @Test
    fun messageCountsCardsAndCopiesAndHandlesAnEmptyCollection() {
        assertEquals(
            "Eliminare \"Inventario\"? Contiene 2 carte (5 copie). L'operazione non si può annullare.",
            CollectionDeletePrompt.message("Inventario", rows = 2, copies = 5),
        )
        assertEquals(
            "Eliminare \"Solo\"? Contiene 1 carta (1 copia). L'operazione non si può annullare.",
            CollectionDeletePrompt.message("Solo", rows = 1, copies = 1),
        )
        assertEquals(
            "Eliminare \"Vuota\"? La collezione è vuota. L'operazione non si può annullare.",
            CollectionDeletePrompt.message("Vuota", rows = 0, copies = 0),
        )
    }

    @Test
    fun cancelAndDismissDoNotDelete() {
        assertFalse(CollectionDeletePrompt.deletes(CollectionDeletePrompt.Action.Cancel))
        assertFalse(CollectionDeletePrompt.deletes(CollectionDeletePrompt.Action.Dismiss))
        assertTrue(CollectionDeletePrompt.deletes(CollectionDeletePrompt.Action.Confirm))
    }
}
