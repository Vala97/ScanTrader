package com.scantrader.app

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CardPrice
import com.scantrader.app.data.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardPriceTest {
    @Test
    fun automaticReferenceDescribesCardTraderQuote() {
        val card = sample(
            priceEur = "0.15",
            priceSource = "CardTrader",
            language = "Italian",
            condition = "Near Mint",
        )
        assertEquals(
            "€0.15 · minimo CardTrader · Near Mint · ITA",
            CardPrice.automaticReference(card),
        )
    }

    @Test
    fun automaticReferenceDescribesScryfallFoil() {
        val card = sample(
            priceEur = "1.00",
            priceEurFoil = "2.50",
            priceSource = "Scryfall",
            foil = true,
        )
        assertEquals(
            "€2.50 · media Cardmarket (Scryfall) foil",
            CardPrice.automaticReference(card),
        )
    }

    @Test
    fun automaticReferenceNullWithoutAmount() {
        assertNull(CardPrice.automaticReference(sample()))
    }

    private fun sample(
        priceEur: String? = null,
        priceEurFoil: String? = null,
        priceSource: String? = null,
        language: String = "English",
        condition: String = "Near Mint",
        foil: Boolean = false,
    ) = CardEntryEntity(
        collectionId = 1L,
        game = Game.MTG,
        name = "Sample",
        setCode = "eld",
        setName = "Throne of Eldraine",
        collectorNumber = "1",
        scryfallId = null,
        tcgplayerId = null,
        imageUrl = null,
        quantity = 1,
        language = language,
        condition = condition,
        foil = foil,
        priceEur = priceEur,
        priceEurFoil = priceEurFoil,
        priceSource = priceSource,
    )
}
