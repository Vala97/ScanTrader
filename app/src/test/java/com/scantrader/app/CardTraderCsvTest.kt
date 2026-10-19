package com.scantrader.app

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.export.CardTraderCsv
import com.scantrader.app.data.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTraderCsvTest {
    @Test
    fun escapesCommasAndQuotes() {
        assertEquals("\"Lightning, Bolt\"", CardTraderCsv.escape("Lightning, Bolt"))
        assertEquals("\"He said \"\"hi\"\"\"", CardTraderCsv.escape("He said \"hi\""))
        assertEquals("plain", CardTraderCsv.escape("plain"))
    }

    @Test
    fun buildsMtgCsvWithScryfallIdAndPrice() {
        val csv = CardTraderCsv.build(
            listOf(
                CardEntryEntity(
                    collectionId = 1,
                    game = Game.MTG,
                    name = "Lightning Bolt",
                    setCode = "lea",
                    setName = "Limited Edition Alpha",
                    collectorNumber = "161",
                    scryfallId = "abc-123",
                    tcgplayerId = null,
                    imageUrl = null,
                    quantity = 2,
                    language = "English",
                    condition = "Near Mint",
                    foil = false,
                    priceEur = "1.50",
                    priceEurFoil = "4.00",
                ),
            ),
            Game.MTG,
        )
        assertTrue(csv.startsWith("scryfall_id,name,expansion_code,collector_number,quantity,language,condition,foil,price"))
        assertTrue(csv.contains("abc-123,Lightning Bolt,lea,161,2,English,Near Mint,false,1.50"))
    }

    @Test
    fun usesFoilPriceWhenFoil() {
        val csv = CardTraderCsv.build(
            listOf(
                CardEntryEntity(
                    collectionId = 1,
                    game = Game.MTG,
                    name = "Bolt",
                    setCode = "m21",
                    setName = "Core",
                    collectorNumber = "1",
                    scryfallId = "id",
                    tcgplayerId = null,
                    imageUrl = null,
                    quantity = 1,
                    language = "English",
                    condition = "Near Mint",
                    foil = true,
                    priceEur = "0.20",
                    priceEurFoil = "0.85",
                ),
            ),
            Game.MTG,
        )
        assertTrue(csv.contains("true,0.85"))
    }

    @Test
    fun splitsPokemonColumns() {
        val csv = CardTraderCsv.build(
            listOf(
                CardEntryEntity(
                    collectionId = 1,
                    game = Game.POKEMON,
                    name = "Pikachu",
                    setCode = "base1",
                    setName = "Base",
                    collectorNumber = "58",
                    scryfallId = null,
                    tcgplayerId = "12345",
                    imageUrl = null,
                    quantity = 1,
                    language = "English",
                    condition = "Slightly Played",
                    foil = true,
                    priceEur = "2.00",
                ),
            ),
            Game.POKEMON,
        )
        assertTrue(csv.contains("name,expansion_code,collector_number,quantity,language,condition,foil,tcgplayer_id,price"))
        assertTrue(csv.contains("Pikachu,base1,58,1,English,Slightly Played,true,12345,2.00"))
    }
}
