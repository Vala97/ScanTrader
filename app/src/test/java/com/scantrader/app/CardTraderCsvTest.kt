package com.scantrader.app

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.export.CardTraderCsv
import com.scantrader.app.data.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun buildsMtgCsvWithScryfallIdAndPriceCents() {
        val csv = CardTraderCsv.build(listOf(mtgEntry()), Game.MTG)
        assertTrue(
            csv.startsWith(
                "scryfall_id,name,expansion_code,collector_number,quantity,language,condition,foil,price_cents",
            ),
        )
        assertTrue(csv.contains("abc-123,Lightning Bolt,lea,161,2,en,Near Mint,false,150"))
    }

    @Test
    fun usesFoilPriceWhenFoilAndLeavesNonPositiveEmpty() {
        val foil = CardTraderCsv.build(
            listOf(
                mtgEntry(
                    name = "Bolt",
                    setCode = "m21",
                    collectorNumber = "1",
                    scryfallId = "id",
                    quantity = 1,
                    foil = true,
                    priceEur = "0.20",
                    priceEurFoil = "0.85",
                ),
            ),
            Game.MTG,
        )
        assertTrue(foil.contains("true,85"))
        assertFalse(foil.contains("0.20"))
        assertFalse(foil.contains(",20"))

        val missingFoil = CardTraderCsv.build(
            listOf(
                mtgEntry(
                    name = "Bolt",
                    foil = true,
                    priceEur = "0.20",
                    priceEurFoil = null,
                ),
            ),
            Game.MTG,
        )
        assertTrue(missingFoil.lines()[1].endsWith(",true,"))
        assertNull(CardTraderCsv.priceToCents("0"))
        assertNull(CardTraderCsv.priceToCents("-1"))
        assertEquals("350", CardTraderCsv.priceToCents("3,50"))
    }

    @Test
    fun splitsPokemonColumnsAndMapsDamagedToPoor() {
        val csv = CardTraderCsv.build(
            listOf(
                pokemonEntry(priceEurFoil = "2.00"),
                pokemonEntry(
                    name = "Charizard",
                    collectorNumber = "4",
                    language = "Japanese",
                    condition = "Damaged",
                    foil = false,
                    priceEur = "0",
                ),
            ),
            Game.POKEMON,
        )
        assertTrue(
            csv.contains(
                "name,expansion_code,collector_number,quantity,language,condition,foil,tcgplayer_id,price_cents",
            ),
        )
        assertTrue(csv.contains("Pikachu,base1,58,1,en,Slightly Played,true,12345,200"))
        assertTrue(csv.contains("Charizard,base1,4,1,jp,Poor,false,12345,"))
    }

    @Test
    fun mapsStoredLanguageNamesToCardTraderCodes() {
        assertEquals("en", CardTraderCsv.exportLanguage("English"))
        assertEquals("it", CardTraderCsv.exportLanguage("Italian"))
        assertEquals("fr", CardTraderCsv.exportLanguage("French"))
        assertEquals("de", CardTraderCsv.exportLanguage("German"))
        assertEquals("es", CardTraderCsv.exportLanguage("Spanish"))
        assertEquals("pt", CardTraderCsv.exportLanguage("Portuguese"))
        assertEquals("jp", CardTraderCsv.exportLanguage("Japanese"))
        assertEquals("kr", CardTraderCsv.exportLanguage("Korean"))
        assertEquals("zh-CN", CardTraderCsv.exportLanguage("Chinese"))
        assertEquals("zh-CN", CardTraderCsv.exportLanguage("zh-cn"))
        assertEquals("Near Mint", CardTraderCsv.exportCondition("Near Mint"))
        assertEquals("Slightly Played", CardTraderCsv.exportCondition("Slightly Played"))
        assertEquals("Moderately Played", CardTraderCsv.exportCondition("Moderately Played"))
        assertEquals("Heavily Played", CardTraderCsv.exportCondition("Heavily Played"))
        assertEquals("Poor", CardTraderCsv.exportCondition("Damaged"))
        assertEquals("Played", CardTraderCsv.exportCondition("Played"))
    }

    @Test
    fun keepsOneFilePerGame() {
        val mixed = listOf(mtgEntry(), pokemonEntry())
        val mtg = CardTraderCsv.build(mixed, Game.MTG)
        val pokemon = CardTraderCsv.build(mixed, Game.POKEMON)
        assertFalse(mtg.contains("Pikachu"))
        assertFalse(pokemon.contains("Lightning Bolt"))
        assertEquals("binder-mtg.csv", CardTraderCsv.filename("Binder", Game.MTG))
        assertEquals("binder-pokemon.csv", CardTraderCsv.filename("Binder", Game.POKEMON))
        assertEquals(
            "Esporta CSV CardTrader · un file per gioco",
            CardTraderCsv.shareTitle(listOf(Game.MTG, Game.POKEMON)),
        )
    }

    private fun mtgEntry(
        name: String = "Lightning Bolt",
        setCode: String = "lea",
        collectorNumber: String = "161",
        scryfallId: String? = "abc-123",
        quantity: Int = 2,
        language: String = "English",
        condition: String = "Near Mint",
        foil: Boolean = false,
        priceEur: String? = "1.50",
        priceEurFoil: String? = "4.00",
    ) = CardEntryEntity(
        collectionId = 1,
        game = Game.MTG,
        name = name,
        setCode = setCode,
        setName = "Limited Edition Alpha",
        collectorNumber = collectorNumber,
        scryfallId = scryfallId,
        tcgplayerId = null,
        imageUrl = null,
        quantity = quantity,
        language = language,
        condition = condition,
        foil = foil,
        priceEur = priceEur,
        priceEurFoil = priceEurFoil,
    )

    private fun pokemonEntry(
        name: String = "Pikachu",
        collectorNumber: String = "58",
        language: String = "English",
        condition: String = "Slightly Played",
        foil: Boolean = true,
        priceEur: String? = "2.00",
        priceEurFoil: String? = null,
    ) = CardEntryEntity(
        collectionId = 1,
        game = Game.POKEMON,
        name = name,
        setCode = "base1",
        setName = "Base",
        collectorNumber = collectorNumber,
        scryfallId = null,
        tcgplayerId = "12345",
        imageUrl = null,
        quantity = 1,
        language = language,
        condition = condition,
        foil = foil,
        priceEur = priceEur,
        priceEurFoil = priceEurFoil,
    )
}
