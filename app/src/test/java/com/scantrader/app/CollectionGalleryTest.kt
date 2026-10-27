package com.scantrader.app

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.model.Game
import com.scantrader.app.ui.collection.COLLECTION_SEARCH_DEBOUNCE_MS
import com.scantrader.app.ui.collection.CollectionBrowseState
import com.scantrader.app.ui.collection.CollectionSort
import com.scantrader.app.ui.collection.browseCollection
import com.scantrader.app.ui.collection.collectionPrice
import com.scantrader.app.ui.collection.collectionSearchDelay
import com.scantrader.app.ui.collection.collectionSections
import com.scantrader.app.ui.collection.collectionSummary
import com.scantrader.app.ui.collection.decodeCollectionBrowse
import com.scantrader.app.ui.collection.encodeCollectionBrowse
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionGalleryTest {
    @Test
    fun groupsByExpansionAndSortsCollectorNumbers() {
        val sections = collectionSections(
            listOf(
                card(id = 1, setCode = "mor", setName = "Morningtide", number = "105a", price = "1.00"),
                card(id = 2, setCode = "bng", setName = "Born of the Gods", number = "10", price = "2.00"),
                card(id = 3, setCode = "MOR", setName = "Morningtide", number = "62", quantity = 3),
                card(id = 4, setCode = "mor", setName = "Morningtide", number = "105"),
            ),
            CollectionSort.COLLECTOR_NUMBER,
        )
        assertEquals(listOf("Born of the Gods", "Morningtide"), sections.map { it.title })
        assertEquals(1, sections[0].copies)
        assertEquals(5, sections[1].copies)
        assertEquals(listOf("62", "105", "105a"), sections[1].cards.map { it.collectorNumber })
    }

    @Test
    fun priceSortPutsTheHighestFirstAndMissingLast() {
        val section = collectionSections(
            listOf(
                card(id = 1, number = "10", price = "0.50"),
                card(id = 2, number = "2", price = null),
                card(id = 3, number = "3", price = "12.00"),
                card(id = 4, number = "4", price = "0"),
            ),
            CollectionSort.PRICE,
        ).single()
        assertEquals(listOf(3L, 1L, 2L, 4L), section.cards.map { it.id })
        assertEquals(BigDecimal("12.00"), collectionPrice(section.cards[0]))
        assertNull(collectionPrice(section.cards[2]))
    }

    @Test
    fun missingSetNameUsesTheSetCode() {
        val section = collectionSections(
            listOf(card(id = 1, setCode = "eld", setName = "  ", number = "12")),
            CollectionSort.COLLECTOR_NUMBER,
        ).single()
        assertEquals("ELD", section.title)
        assertEquals(1, section.copies)
    }

    @Test
    fun foilMagicUsesTheFoilPriceAndPokemonDoesNot() {
        val foil = card(id = 1, foil = true, price = "0.20", priceFoil = "4.00")
        val pokemon = card(
            id = 2,
            game = Game.POKEMON,
            foil = true,
            price = "1.25",
            priceFoil = "9.00",
        )
        assertEquals(BigDecimal("4.00"), collectionPrice(foil))
        assertEquals(BigDecimal("1.25"), collectionPrice(pokemon))
        assertEquals(BigDecimal("1.10"), collectionPrice(foil.copy(manualPriceEur = "1.10")))
        assertEquals(BigDecimal("2.00"), collectionPrice(pokemon.copy(manualPriceEur = "2.00")))
    }

    @Test
    fun searchMatchesThePrintedNameAndTheEnglishName() {
        val italian = card(
            id = 1,
            name = "Druido del Suolo Avvizzito",
            catalogName = "Blightsoil Druid",
        )
        val other = card(id = 2, name = "Shock", catalogName = "Shock", setCode = "m10", setName = "Magic 2010")
        val cards = listOf(italian, other)
        assertEquals(listOf(1L), ids(browseCollection(cards, CollectionBrowseState(query = "suolo"))))
        assertEquals(listOf(1L), ids(browseCollection(cards, CollectionBrowseState(query = "  Blight "))))
        assertEquals(listOf(3L), ids(browseCollection(
            listOf(card(id = 3, name = "Pokémon")),
            CollectionBrowseState(query = "pokemon"),
        )))
        assertTrue(browseCollection(cards, CollectionBrowseState(query = "   ")).copies == 2)
        assertEquals(0L, collectionSearchDelay(" "))
        assertEquals(COLLECTION_SEARCH_DEBOUNCE_MS, collectionSearchDelay("suolo"))
    }

    @Test
    fun filtersStackAndTheSummaryUsesQuantityAndTheManualPrice() {
        val morning = card(
            id = 1,
            name = "A",
            language = "Italian",
            condition = "Near Mint",
            price = "1.50",
            quantity = 2,
        )
        val foil = card(
            id = 2,
            name = "B",
            language = "English",
            condition = "Near Mint",
            foil = true,
            price = "0.20",
            priceFoil = "4.00",
            setCode = "bng",
            setName = "Born of the Gods",
        )
        val damaged = card(id = 3, name = "C", language = "Italian", condition = "Damaged", price = "9.00")
        val cards = listOf(morning, foil, damaged)
        val italianNm = browseCollection(
            cards,
            CollectionBrowseState(languages = setOf("Italian"), conditions = setOf("Near Mint")),
        )
        assertEquals(listOf(1L), ids(italianNm))
        assertEquals(2, italianNm.copies)
        assertEquals(listOf(2L), ids(browseCollection(cards, CollectionBrowseState(foil = true))))
        assertEquals(
            listOf(1L, 3L),
            ids(browseCollection(cards, CollectionBrowseState(sets = setOf("MTG:mor")))),
        )
        val priced = browseCollection(listOf(morning, foil.copy(manualPriceEur = "1.10")), CollectionBrowseState())
        assertEquals(0, priced.total.compareTo(BigDecimal("4.10")))
        assertEquals(3, priced.copies)
        val summary = collectionSummary(priced.copies, priced.total)
        assertTrue(summary.contains("3 copie"))
        assertTrue(summary.contains("4,10"))
    }

    @Test
    fun sortsByNamePriceAndDateAndKeepsExpansionGroupsByDefault() {
        val older = card(
            id = 1,
            name = "Zeta",
            price = "2.00",
            createdAt = 10,
            setCode = "bng",
            setName = "Born of the Gods",
        )
        val newer = card(id = 2, name = "alfa", price = "5.00", createdAt = 20)
        val manual = card(
            id = 3,
            name = "Beta",
            foil = true,
            price = "0.10",
            priceFoil = "9.00",
            createdAt = 15,
        ).copy(manualPriceEur = "0.40")
        val cards = listOf(older, newer, manual)
        val byName = browseCollection(cards, CollectionBrowseState(sort = CollectionSort.NAME))
        assertFalse(byName.grouped)
        assertEquals(listOf("alfa", "Beta", "Zeta"), byName.sections.single().cards.map { it.name })
        val ascending = browseCollection(cards, CollectionBrowseState(sort = CollectionSort.PRICE_ASC))
        assertEquals(listOf(3L, 1L, 2L), ascending.sections.single().cards.map { it.id })
        val descending = browseCollection(cards, CollectionBrowseState(sort = CollectionSort.PRICE))
        assertEquals(listOf(2L, 1L, 3L), descending.sections.single().cards.map { it.id })
        val newest = browseCollection(cards, CollectionBrowseState(sort = CollectionSort.DATE_ADDED))
        assertEquals(listOf(2L, 3L, 1L), newest.sections.single().cards.map { it.id })
        val grouped = browseCollection(cards, CollectionBrowseState())
        assertTrue(grouped.grouped)
        assertEquals(listOf("Born of the Gods", "Morningtide"), grouped.sections.map { it.title })
    }

    @Test
    fun browseStateRoundTripsAndAnUnknownSortFallsBack() {
        val state = CollectionBrowseState(
            query = "druido del",
            sets = setOf("MTG:mor", "POKEMON:sv1"),
            languages = setOf("Italian"),
            conditions = setOf("Near Mint"),
            foil = false,
            sort = CollectionSort.PRICE_ASC,
        )
        assertEquals(state, decodeCollectionBrowse(encodeCollectionBrowse(state)))
        assertEquals(CollectionBrowseState(), decodeCollectionBrowse(null))
        assertEquals(CollectionBrowseState(), decodeCollectionBrowse("0\nnope"))
        val broken = encodeCollectionBrowse(state).replace("PRICE_ASC", "COLOUR")
        assertEquals(CollectionSort.COLLECTOR_NUMBER, decodeCollectionBrowse(broken).sort)
        assertFalse(CollectionBrowseState().active)
        assertTrue(state.active)
    }

    private fun ids(result: com.scantrader.app.ui.collection.CollectionBrowseResult) =
        result.sections.flatMap { it.cards }.map { it.id }

    private fun card(
        id: Long,
        setCode: String = "mor",
        setName: String = "Morningtide",
        number: String = "1",
        quantity: Int = 1,
        price: String? = "1.00",
        priceFoil: String? = null,
        foil: Boolean = false,
        game: Game = Game.MTG,
        name: String = "Card $id",
        catalogName: String? = null,
        language: String = "Italian",
        condition: String = "Near Mint",
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
        language = language,
        condition = condition,
        foil = foil,
        priceEur = price,
        priceEurFoil = priceFoil,
        catalogName = catalogName,
        createdAt = createdAt,
    )
}
