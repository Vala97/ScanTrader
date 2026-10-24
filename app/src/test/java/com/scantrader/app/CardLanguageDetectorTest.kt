package com.scantrader.app

import com.scantrader.app.data.catalog.CardLanguageDetector
import com.scantrader.app.data.model.CardLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardLanguageDetectorTest {
    @Test
    fun italianPrintedNameBeatsTheEnglishOracleName() {
        val detected = CardLanguageDetector.detect(
            ocrName = "Ostinazione Mortale",
            englishName = "Mortal Obstinacy",
            printed = mapOf(CardLanguage.ITALIAN to "Ostinazione Mortale"),
        )
        assertEquals(CardLanguage.ITALIAN, detected)
    }

    @Test
    fun englishTitleStaysEnglishWhenItalianPrintIsDifferent() {
        val detected = CardLanguageDetector.detect(
            ocrName = "Lightning Bolt",
            englishName = "Lightning Bolt",
            printed = mapOf(
                CardLanguage.ITALIAN to "Lampo",
                CardLanguage.GERMAN to "Blitzschlag",
            ),
        )
        assertEquals(CardLanguage.ENGLISH, detected)
    }

    @Test
    fun uncertainOcrFallsBack() {
        assertNull(
            CardLanguageDetector.detect(
                ocrName = "xyzabc qqq",
                englishName = "Mortal Obstinacy",
                printed = mapOf(CardLanguage.ITALIAN to "Ostinazione Mortale"),
            ),
        )
    }

    @Test
    fun toggleOffKeepsTheLastLanguage() {
        assertEquals(
            CardLanguage.FRENCH,
            CardLanguageDetector.choose(
                detectEnabled = false,
                detected = CardLanguage.ITALIAN,
                lastChosen = CardLanguage.FRENCH,
            ),
        )
    }

    @Test
    fun toggleOnUsesDetectionAndFallsBackWhenUnsure() {
        assertEquals(
            CardLanguage.ITALIAN,
            CardLanguageDetector.choose(true, CardLanguage.ITALIAN, CardLanguage.ENGLISH),
        )
        assertEquals(
            CardLanguage.GERMAN,
            CardLanguageDetector.choose(true, null, CardLanguage.GERMAN),
        )
    }

    @Test
    fun kilowattrelDiKissaraOcrLogIsItalian() {
        val detected = CardLanguageDetector.detect(
            ocrName = "Kilowattrel di Kissara",
            englishName = "Iono's Kilowattrel",
            printed = mapOf(
                CardLanguage.ITALIAN to "Kilowattrel di Kissara",
                CardLanguage.FRENCH to "Fulgulairo de Mashynn",
                CardLanguage.GERMAN to "Voltrean von Enigmara",
                CardLanguage.SPANISH to "Kilowattrel de e-Nigma",
                CardLanguage.PORTUGUESE to "Kilowattrel da Kissara",
            ),
            ocrText = KILOWATTREL_OCR_LOG,
            pokemonSignals = true,
        )
        assertEquals(CardLanguage.ITALIAN, detected)
    }

    @Test
    fun kilowattrelOcrLogIsItalianFromRulesTextWhenTheTitleIsAmbiguous() {
        val detected = CardLanguageDetector.detect(
            ocrName = "Kilowattrel di Kissara",
            englishName = "Kissara's Kilowattrel",
            printed = mapOf(CardLanguage.ITALIAN to "Kilowattrel di Kissara"),
            ocrText = KILOWATTREL_OCR_LOG,
            pokemonSignals = true,
        )
        assertEquals(CardLanguage.ITALIAN, detected)
    }

    @Test
    fun pokemonRulesWordsSelectThePrintedLanguage() {
        assertEquals(CardLanguage.ENGLISH, pokemon("Evolves from Wattrel\nWeakness\nResistance\nRetreat"))
        assertEquals(CardLanguage.FRENCH, pokemon("Niveau\nÉvolue de\nFaiblesse\nRetraite"))
        assertEquals(CardLanguage.GERMAN, pokemon("Entwickelt sich aus\nSchwäche\nResistenz\nRückzug"))
        assertEquals(CardLanguage.SPANISH, pokemon("Evoluciona de\nDebilidad\nRetirada"))
        assertEquals(CardLanguage.PORTUGUESE, pokemon("Evolui de\nFraqueza\nRecuo"))
        assertEquals(CardLanguage.JAPANESE, pokemon("ワザ\nにげる\n弱点"))
        assertEquals(CardLanguage.KOREAN, pokemon("약점\n후퇴"))
        assertEquals(CardLanguage.CHINESE_T, pokemon("弱點\n抵抗\n撤退"))
        assertEquals(CardLanguage.CHINESE_S, pokemon("弱点\n抵抗\n撤退"))
    }

    @Test
    fun uncertainPokemonTextFallsBack() {
        assertNull(
            CardLanguageDetector.detect(
                ocrName = "Pikachu",
                englishName = "Pikachu",
                printed = mapOf(CardLanguage.ITALIAN to "Pikachu"),
                ocrText = "Pikachu\n120",
                pokemonSignals = true,
            ),
        )
        assertEquals(
            CardLanguage.FRENCH,
            CardLanguageDetector.choose(true, null, CardLanguage.FRENCH),
        )
    }

    @Test
    fun catalogCodesCoverPokemonLanguages() {
        assertEquals(CardLanguage.ITALIAN, CardLanguage.fromCatalogCode("it"))
        assertEquals(CardLanguage.KOREAN, CardLanguage.fromCatalogCode("ko"))
        assertEquals(CardLanguage.CHINESE_S, CardLanguage.fromCatalogCode("zh-cn"))
        assertEquals(CardLanguage.CHINESE_T, CardLanguage.fromCatalogCode("zh-tw"))
        assertEquals(
            listOf("en", "it", "fr", "de", "es", "pt", "ja", "ko", "zh-cn", "zh-tw"),
            com.scantrader.app.data.catalog.pokemonTcgdexLanguages(),
        )
    }

    private fun pokemon(text: String): CardLanguage? = CardLanguageDetector.detect(
        ocrName = null,
        englishName = null,
        printed = emptyMap(),
        ocrText = text,
        pokemonSignals = true,
    )

    private companion object {
        val KILOWATTREL_OCR_LOG = """
            A!
            FASEJ
            Kilowattrel ai Kissara
            Sievolve da Wattrel di Kissara
            Pescalampo
            debolezza ×2
            resistenza
            costo di ritirata
            Illus. kantaro
            O ASCH | 072/217 *
        """.trimIndent()
    }
}
