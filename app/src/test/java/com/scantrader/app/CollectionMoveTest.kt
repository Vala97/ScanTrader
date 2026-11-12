package com.scantrader.app

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CollectionMove
import com.scantrader.app.data.model.Game
import com.scantrader.app.ui.collection.CollectionSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionMoveTest {
    @Test
    fun changingFiltersKeepsTheSelectionAndCountsHiddenCopies() {
        val cards = listOf(card(1, qty = 2), card(2, qty = 4), card(3, qty = 1))
        var selected = CollectionSelection.toggle(emptySet(), 1)
        selected = CollectionSelection.selectVisible(selected, setOf(2, 3))
        val visibleAfter = setOf(2L)
        val count = CollectionSelection.count(selected, cards, visibleAfter)
        assertEquals(setOf(1L, 2L, 3L), selected)
        assertEquals(3, count.rows)
        assertEquals(7, count.copies)
        assertEquals(2, count.hiddenRows)
        assertEquals(3, count.hiddenCopies)
        selected = CollectionSelection.deselectVisible(selected, visibleAfter)
        assertEquals(setOf(1L, 3L), selected)
        assertFalse(1L in CollectionSelection.deselectVisible(selected, setOf(1L)))
    }

    @Test
    fun deleteConfirmCountsHiddenCopies() {
        val count = CollectionSelection.count(
            selected = setOf(1L, 2L),
            all = listOf(card(1, qty = 2), card(2, qty = 3)),
            visibleIds = setOf(1L),
        )
        assertEquals(
            "Eliminare 2 carte (5 copie)? Comprende 3 copie nascoste dai filtri. L'azione non si annulla.",
            CollectionSelection.deleteConfirm(count),
        )
    }

    @Test
    fun wholeMoveKeepsTheRowAndPartialMoveKeepsTheRest() {
        val source = card(id = 8, qty = 5, manual = "9.00")
        val whole = CollectionMove.plan(
            requests = listOf(CollectionMove.Request(8, 5)),
            sourceRows = listOf(source),
            destinationRows = emptyList(),
            destinationId = 2L,
        )
        assertEquals(0, whole.source.size)
        assertEquals(1, whole.destination.size)
        assertEquals(8L, whole.destination.single().id)
        assertEquals(2L, whole.destination.single().collectionId)
        assertEquals(5, whole.copiesMoved)
        assertEquals("9.00", whole.destination.single().manualPriceEur)

        val partial = CollectionMove.plan(
            requests = listOf(CollectionMove.Request(8, 2)),
            sourceRows = listOf(source),
            destinationRows = emptyList(),
            destinationId = 2L,
        )
        assertEquals(3, partial.source.single().quantity)
        assertEquals(2, partial.destination.single().quantity)
        assertEquals(5, partial.source.single().quantity + partial.destination.single().quantity)
        assertEquals("9.00", partial.source.single().manualPriceEur)
        assertEquals("9.00", partial.destination.single().manualPriceEur)
    }

    @Test
    fun compatibleRowsMergeAndVariantsStaySeparate() {
        val moving = card(id = 1, qty = 2, language = "Italian", foil = false)
        val same = card(id = 9, qty = 3, language = "Italian", foil = false, collectionId = 2L)
        val otherLanguage = card(id = 10, qty = 1, language = "English", foil = false, collectionId = 2L)
        val foil = card(id = 11, qty = 1, language = "Italian", foil = true, collectionId = 2L)
        val merged = CollectionMove.plan(
            requests = listOf(CollectionMove.Request(1, 2)),
            sourceRows = listOf(moving),
            destinationRows = listOf(same, otherLanguage, foil),
            destinationId = 2L,
        )
        assertTrue(merged.source.isEmpty())
        assertEquals(3, merged.destination.size)
        val italian = merged.destination.single { it.language == "Italian" && !it.foil }
        assertEquals(5, italian.quantity)
        assertEquals(1, merged.destination.single { it.language == "English" }.quantity)
        assertEquals(1, merged.destination.single { it.foil }.quantity)
        assertEquals(2, merged.copiesMoved)
        assertEquals(2 + 3 + 1 + 1, merged.destination.sumOf { it.quantity })
    }

    @Test
    fun tooManyCopiesAreCutToWhatRemainsAndCancelMovesNothing() {
        val source = card(4, qty = 2)
        val clamped = CollectionMove.plan(
            requests = listOf(CollectionMove.Request(4, 9)),
            sourceRows = listOf(source),
            destinationRows = emptyList(),
            destinationId = 2L,
        )
        assertEquals(2, clamped.copiesMoved)
        assertTrue(clamped.source.isEmpty())
        assertNull(CollectionMove.clamp(0, 2))
        assertNull(CollectionMove.clamp(-1, 2))
        val cancelled = CollectionMove.plan(
            requests = emptyList(),
            sourceRows = listOf(source),
            destinationRows = emptyList(),
            destinationId = 2L,
        )
        assertEquals(0, cancelled.copiesMoved)
        assertEquals(listOf(source), cancelled.source)
    }

    @Test
    fun unsentCopiesMoveBeforeCopiesAlreadySent() {
        assertEquals(0, CollectionMove.sentMoved(sourceQuantity = 5, alreadySent = 3, moving = 2))
        assertEquals(2, CollectionMove.sentMoved(sourceQuantity = 5, alreadySent = 3, moving = 4))
        assertEquals(3, CollectionMove.sentMoved(sourceQuantity = 5, alreadySent = 3, moving = 5))
    }

    private fun card(
        id: Long,
        qty: Int,
        language: String = "Italian",
        foil: Boolean = false,
        manual: String? = null,
        collectionId: Long = 1L,
    ) = CardEntryEntity(
        id = id,
        collectionId = collectionId,
        game = Game.MTG,
        name = "Lightning Bolt",
        setCode = "lea",
        setName = "Alpha",
        collectorNumber = "161",
        scryfallId = "bolt",
        tcgplayerId = null,
        imageUrl = null,
        quantity = qty,
        language = language,
        condition = "Near Mint",
        foil = foil,
        manualPriceEur = manual,
    )
}
