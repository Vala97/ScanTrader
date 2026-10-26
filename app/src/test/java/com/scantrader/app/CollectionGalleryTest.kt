package com.scantrader.app

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.model.Game
import com.scantrader.app.ui.collection.CollectionSort
import com.scantrader.app.ui.collection.collectionPrice
import com.scantrader.app.ui.collection.collectionSections
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }

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
    ) = CardEntryEntity(
        id = id,
        collectionId = 1,
        game = game,
        name = "Card $id",
        setCode = setCode,
        setName = setName,
        collectorNumber = number,
        scryfallId = null,
        tcgplayerId = null,
        imageUrl = null,
        quantity = quantity,
        language = "Italian",
        condition = "Near Mint",
        foil = foil,
        priceEur = price,
        priceEurFoil = priceFoil,
    )
}
