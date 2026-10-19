package com.scantrader.app

import com.scantrader.app.data.catalog.ExpansionCodes
import com.scantrader.app.data.catalog.PokemonCatalog
import com.scantrader.app.data.catalog.PokemonSets
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.OcrParser
import com.scantrader.app.vision.MatchFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PokemonCatalogTest {
    @Test
    fun choosesLanguagesFromScriptAndPrintedSet() {
        assertEquals(listOf("en", "it"), PokemonCatalog.languages("Pikachu", null))
        assertEquals(listOf("en", "it"), PokemonCatalog.languages("Muk del Team Rocket", "dri"))
        assertEquals(listOf("ja", "zh-tw", "zh-cn"), PokemonCatalog.languages("ピカチュウ", null))
        assertEquals(listOf("zh-tw", "zh-cn", "ja"), PokemonCatalog.languages("皮卡丘", null))
        assertEquals(listOf("ja", "zh-tw", "zh-cn"), PokemonCatalog.languages(null, "SV2a"))
        assertEquals(listOf("zh-cn", "zh-tw"), PokemonCatalog.languages(null, "CSV3C"))
        assertTrue(PokemonCatalog.nameQueries("ピカチュウ") == listOf("ピカチュウ"))
        assertTrue(PokemonCatalog.nameQueries("Muk del Team Rocket").contains("Muk"))
    }

    @Test
    fun internationalPrintedCodesUseTcgdexIds() {
        assertEquals(listOf("sv03"), PokemonSets.recognizedIds("OBF"))
        assertEquals(listOf("sv10"), PokemonSets.recognizedIds("dri"))
        assertEquals(listOf("sv01"), PokemonSets.recognizedIds("svi"))
        assertEquals(listOf("sv03.5"), PokemonSets.recognizedIds("mew"))
        assertEquals(listOf("sv06.5"), PokemonSets.recognizedIds("sfa"))
        assertEquals(listOf("SV2a"), PokemonSets.idsFor("SV2a", "ja"))
        assertEquals(listOf("sv03-1", "sv03-001"), PokemonCatalog.cardIds("sv03", "1"))
        assertTrue(PokemonSets.sameSet("dri", "sv10"))
        assertFalse(PokemonSets.sameSet("obf", "sv10"))
    }

    @Test
    fun numberMatchIgnoresPaddingAndRejectsPrefixes() {
        assertTrue(PokemonCatalog.numberMatches("124", "124"))
        assertTrue(PokemonCatalog.numberMatches("1", "001"))
        assertTrue(PokemonCatalog.numberMatches("12", "TG12"))
        assertFalse(PokemonCatalog.numberMatches("9", "90"))
        assertFalse(PokemonCatalog.numberMatches("12", "123"))
        assertTrue(PokemonCatalog.nameMatches("フシギダネ", "フシギダネ"))
        assertTrue(PokemonCatalog.nameMatches("皮卡丘ex", "皮卡丘"))
    }

    @Test
    fun cardTraderKeepsJapaneseIdsDistinctFromInternational() {
        assertEquals("obf", ExpansionCodes.pokemon("sv03"))
        assertEquals("dri", ExpansionCodes.pokemon("sv10"))
        assertEquals("sv10", ExpansionCodes.pokemon("SV10"))
        assertEquals("sv2a", ExpansionCodes.pokemon("SV2a"))
        assertEquals("base1", ExpansionCodes.pokemon("base1"))
    }

    @Test
    fun fusionMatchesPrintedCodeAndCjkName() {
        val parsed = ParsedCardText(
            nameGuess = "Team Rocket's Muk",
            collectorNumber = "124",
            setCode = "dri",
            setCardCount = 182,
        )
        val card = IdentifiedCard(
            game = Game.POKEMON,
            name = "Team Rocket's Muk",
            setCode = "sv10",
            setName = "Destined Rivals",
            collectorNumber = "124",
        )
        assertTrue(MatchFusion.ocrAgreement(card, parsed) >= 0.9f)

        val japanese = ParsedCardText("フシギダネ", "1", "SV2a")
        val jaCard = card.copy(name = "フシギダネ", setCode = "SV2a", collectorNumber = "001")
        assertTrue(MatchFusion.ocrAgreement(jaCard, japanese) >= 0.9f)
    }

    @Test
    fun parserPrefersCjkName() {
        val text = "HP 80\nフシギダネ\nたねポケモン"
        val parsed = OcrParser.parse(text, Game.POKEMON)
        assertEquals("フシギダネ", parsed.nameGuess)
    }
}
