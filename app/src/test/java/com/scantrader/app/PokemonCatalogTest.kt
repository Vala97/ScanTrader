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

    @Test
    fun latinNoiseDoesNotQueryAsianCatalogs() {
        assertEquals(listOf("en", "it"), PokemonCatalog.languages("Kilowattrel di Kissara", "uor"))
        assertEquals(listOf("en", "it"), PokemonCatalog.scriptLanguages("KilowattrelaKiasarn 120ヶ"))
        assertEquals(listOf("me02.5"), PokemonSets.idsForCardCount(217))
        assertTrue(PokemonSets.recognizedIds("asc").contains("me02.5"))
        assertTrue(OcrParser.setCodeCandidates("uor").contains("asc"))
    }

    @Test
    fun classicSetTotalsMapToWotcIds() {
        assertEquals(listOf("base1"), PokemonSets.idsForCardCount(102))
        assertEquals(listOf("base2"), PokemonSets.idsForCardCount(64))
        assertEquals(listOf("base5"), PokemonSets.idsForCardCount(83))
        assertTrue(PokemonSets.idsForCardCount(132).contains("base4"))
    }

    @Test
    fun parsesVintageBottomRightFractionWithoutSetCode() {
        // Base / Rocket: number bottom-right, often no letter set code.
        val full = """
            Dark Charizard
            Stage 2 Pokémon
        """.trimIndent()
        val bottomRight = "4/82 ★"
        val parsed = OcrParser.parse(full, Game.POKEMON, bottomRight)
        assertEquals("4", parsed.collectorNumber)
        assertEquals(82, parsed.setCardCount)
        assertTrue(parsed.nameGuess!!.contains("Charizard", ignoreCase = true))
    }

    @Test
    fun penalizesCandidatesWithoutNameOverlap() {
        val withName = PokemonCatalog.score(
            cardName = "Kilowattrel di Kissara",
            cardNumber = "072",
            setId = "me02.5",
            printedTotal = 217,
            queryName = "Kilowattrel di Kissara",
            queryNumber = "72",
            querySetIds = listOf("me02.5"),
            setCardCount = 217,
        )
        val wrongName = PokemonCatalog.score(
            cardName = "Swoobat",
            cardNumber = "072",
            setId = "xy8",
            printedTotal = null,
            queryName = "Kilowattrel di Kissara",
            queryNumber = "72",
            querySetIds = listOf("me02.5"),
            setCardCount = 217,
        )
        assertTrue(withName > wrongName)
        assertTrue(wrongName < 20)
        assertTrue(PokemonCatalog.nameMatches("Kilowattrel di Kissara", "Kilowattrel"))
        assertFalse(PokemonCatalog.nameMatches("Swoobat", "Kilowattrel di Kissara"))
        assertTrue(PokemonCatalog.nameMatches("Kilowattrel di Kissara", "Kilowattrel Kisara"))
        assertTrue(PokemonCatalog.nameMatches("Kissara's Kilowattrel", "Kilowattrel di Kissara"))
        assertTrue(PokemonCatalog.nameQueries("Kilowattrel di Kissara").contains("Kissara's Kilowattrel"))
        assertEquals(listOf("en", "it"), PokemonCatalog.languages("Kilowattrel di Kissara", "asc"))
        assertEquals("asc", PokemonSets.canonicalizePrinted("asch"))
        assertEquals("asc", PokemonSets.canonicalizePrinted("DASC"))
        assertEquals("asc", PokemonSets.canonicalizePrinted("oasc"))
        assertEquals("dri", PokemonSets.canonicalizePrinted("dri"))
    }
}
