package com.scantrader.app

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.model.Game
import com.scantrader.app.ui.collection.CollectionBrowseState
import com.scantrader.app.ui.collection.CollectionFastScrollMath
import com.scantrader.app.ui.collection.CollectionGridRow
import com.scantrader.app.ui.collection.CollectionSort
import com.scantrader.app.ui.collection.browseCollection
import com.scantrader.app.ui.collection.collectionGameCollapseKey
import com.scantrader.app.ui.collection.collectionGridRows
import com.scantrader.app.ui.collection.collectionNameInitial
import com.scantrader.app.ui.collection.collectionScrollLabel
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionFastScrollTest {
    private val utc: ZoneId = ZoneOffset.UTC

    // ---- position math ----

    @Test
    fun fractionFollowsTheFirstVisibleItem() {
        assertEquals(0f, CollectionFastScrollMath.fractionFor(0, 0f, 12, 112), 0f)
        assertEquals(0.5f, CollectionFastScrollMath.fractionFor(50, 0f, 12, 112), 0.0001f)
        assertEquals(1f, CollectionFastScrollMath.fractionFor(100, 0f, 12, 112), 0f)
        // Part of the first item scrolled away moves the thumb a little.
        assertEquals(0.505f, CollectionFastScrollMath.fractionFor(50, 0.5f, 12, 112), 0.0001f)
        // Past the end (overscroll) and empty or fitting lists stay in range.
        assertEquals(1f, CollectionFastScrollMath.fractionFor(110, 0f, 12, 112), 0f)
        assertEquals(0f, CollectionFastScrollMath.fractionFor(0, 0f, 0, 0), 0f)
        assertEquals(0f, CollectionFastScrollMath.fractionFor(0, 0f, 12, 10), 0f)
    }

    @Test
    fun indexForThumbPosition() {
        assertEquals(0, CollectionFastScrollMath.indexFor(0f, 112, 12))
        assertEquals(50, CollectionFastScrollMath.indexFor(0.5f, 112, 12))
        assertEquals(111, CollectionFastScrollMath.indexFor(1f, 112, 12))
        assertEquals(0, CollectionFastScrollMath.indexFor(-0.3f, 112, 12))
        assertEquals(111, CollectionFastScrollMath.indexFor(1.7f, 112, 12))
        assertEquals(0, CollectionFastScrollMath.indexFor(0.5f, 0, 0))
        assertEquals(0, CollectionFastScrollMath.indexFor(0.5f, 1, 1))
        assertEquals(1, CollectionFastScrollMath.indexFor(1f, 2, 2))
    }

    @Test
    fun indexAndFractionRoundTrip() {
        for (index in 0..100) {
            val f = CollectionFastScrollMath.fractionFor(index, 0f, 12, 112)
            assertEquals(index, CollectionFastScrollMath.indexFor(f, 112, 12).coerceAtMost(100))
        }
    }

    @Test
    fun touchAndThumbPixels() {
        // Track of 1000 px, thumb of 100 px: the thumb centre follows the finger.
        assertEquals(0f, CollectionFastScrollMath.fractionForTouch(0f, 1000f, 100f), 0f)
        assertEquals(0f, CollectionFastScrollMath.fractionForTouch(50f, 1000f, 100f), 0f)
        assertEquals(0.5f, CollectionFastScrollMath.fractionForTouch(500f, 1000f, 100f), 0.0001f)
        assertEquals(1f, CollectionFastScrollMath.fractionForTouch(990f, 1000f, 100f), 0f)
        assertEquals(0f, CollectionFastScrollMath.fractionForTouch(10f, 80f, 100f), 0f)
        assertEquals(0f, CollectionFastScrollMath.thumbTop(0f, 1000f, 100f), 0f)
        assertEquals(450f, CollectionFastScrollMath.thumbTop(0.5f, 1000f, 100f), 0.001f)
        assertEquals(900f, CollectionFastScrollMath.thumbTop(1f, 1000f, 100f), 0f)
        assertEquals(900f, CollectionFastScrollMath.thumbTop(2f, 1000f, 100f), 0f)
    }

    // ---- visibility ----

    @Test
    fun hiddenForShortListsOrWhenEverythingFits() {
        assertFalse(CollectionFastScrollMath.visible(59, canScroll = true))
        assertTrue(CollectionFastScrollMath.visible(60, canScroll = true))
        assertFalse(CollectionFastScrollMath.visible(500, canScroll = false))
        assertFalse(CollectionFastScrollMath.visible(0, canScroll = false))
    }

    // ---- rows ----

    @Test
    fun groupedRowsPutOneHeaderPerGameThenEachExpansion() {
        val result = browseCollection(
            listOf(
                card(1, setCode = "mor", setName = "Morningtide", number = "2"),
                card(2, setCode = "bng", setName = "Born of the Gods", number = "1"),
                card(3, setCode = "mor", setName = "Morningtide", number = "1", quantity = 2),
                card(4, setCode = "sv1", setName = "Scarlet & Violet", game = Game.POKEMON),
            ),
            CollectionBrowseState(),
        )
        val rows = collectionGridRows(result, CollectionSort.COLLECTOR_NUMBER, emptySet(), utc)
        assertEquals(
            listOf<Any>("game-MTG", "section-MTG:bng", 2L, "section-MTG:mor", 3L, 1L, "game-POKEMON", "section-POKEMON:sv1", 4L),
            rows.map { it.key },
        )
        val magic = rows.first() as CollectionGridRow.GameHeader
        assertEquals(4, magic.copies)
        assertEquals("Magic", magic.label)
        // Cards carry their expansion as the bubble text.
        assertEquals(listOf("Born of the Gods", "Morningtide", "Morningtide"), rows.filterIsInstance<CollectionGridRow.Card>().take(3).map { it.label })
    }

    @Test
    fun collapsedGamesAndExpansionsDropTheirCards() {
        val result = browseCollection(
            listOf(
                card(1, setCode = "mor", setName = "Morningtide"),
                card(2, setCode = "bng", setName = "Born of the Gods"),
                card(3, setCode = "sv1", setName = "Scarlet & Violet", game = Game.POKEMON),
            ),
            CollectionBrowseState(),
        )
        val collapsed = setOf("s:MTG:bng", collectionGameCollapseKey(Game.POKEMON))
        val rows = collectionGridRows(result, CollectionSort.COLLECTOR_NUMBER, collapsed, utc)
        assertEquals(listOf<Any>("game-MTG", "section-MTG:bng", "section-MTG:mor", 1L, "game-POKEMON"), rows.map { it.key })
        assertFalse((rows[1] as CollectionGridRow.SetHeader).open)
        assertFalse((rows.last() as CollectionGridRow.GameHeader).open)
    }

    @Test
    fun flatSortsHaveCardsOnly() {
        val result = browseCollection(
            listOf(card(1, name = "Zur"), card(2, name = "Éowyn"), card(3, name = "1996 World Champion")),
            CollectionBrowseState(sort = CollectionSort.NAME),
        )
        val rows = collectionGridRows(result, CollectionSort.NAME, emptySet(), utc)
        assertTrue(rows.all { it is CollectionGridRow.Card })
        assertEquals(listOf("#", "E", "Z"), rows.map { it.label })
    }

    // ---- labels ----

    @Test
    fun nameInitialFoldsAccentsAndGroupsTheRest() {
        assertEquals("E", collectionNameInitial("Éowyn"))
        assertEquals("A", collectionNameInitial("  ancestral recall"))
        assertEquals("#", collectionNameInitial("1996 World Champion"))
        assertEquals("#", collectionNameInitial("ジェイス"))
        assertEquals("#", collectionNameInitial(""))
    }

    @Test
    fun labelsFollowTheSort() {
        val dates = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ITALY).withZone(utc)
        val added = LocalDate.of(2026, 11, 12).atStartOfDay(utc).toInstant().toEpochMilli()
        val withPrice = card(1, name = "Glade", price = "12.5", createdAt = added)
        val noPrice = card(2, price = null)
        assertEquals("Morningtide", collectionScrollLabel(withPrice, CollectionSort.COLLECTOR_NUMBER, "Morningtide", dates))
        assertEquals("MOR", collectionScrollLabel(withPrice, CollectionSort.COLLECTOR_NUMBER, "", dates))
        assertEquals("G", collectionScrollLabel(withPrice, CollectionSort.NAME, "x", dates))
        assertEquals("12,50 €", collectionScrollLabel(withPrice, CollectionSort.PRICE, "x", dates))
        assertEquals("12,50 €", collectionScrollLabel(withPrice, CollectionSort.PRICE_ASC, "x", dates))
        assertEquals("Senza prezzo", collectionScrollLabel(noPrice, CollectionSort.PRICE, "x", dates))
        assertEquals("12 nov 2026", collectionScrollLabel(withPrice, CollectionSort.DATE_ADDED, "x", dates))
    }

    private fun card(
        id: Long,
        setCode: String = "mor",
        setName: String = "Morningtide",
        number: String = "1",
        quantity: Int = 1,
        price: String? = "1.00",
        game: Game = Game.MTG,
        name: String = "Card $id",
        createdAt: Long = id,
    ) = CardEntryEntity(
        id = id,
        collectionId = 1,
        game = game,
        name = name,
        setCode = setCode,
        setName = setName,
        collectorNumber = number,
        scryfallId = null,
        tcgplayerId = null,
        imageUrl = null,
        quantity = quantity,
        language = "Italian",
        condition = "Near Mint",
        foil = false,
        priceEur = price,
        priceEurFoil = null,
        catalogName = null,
        createdAt = createdAt,
    )
}
