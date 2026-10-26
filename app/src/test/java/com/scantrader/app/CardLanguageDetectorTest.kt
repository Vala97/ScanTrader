package com.scantrader.app

import com.scantrader.app.data.catalog.CardLanguageDetector
import com.scantrader.app.data.model.CardLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardLanguageDetectorTest {
    @Test
    fun morningtideItalianTitleIsItalianWhenScryfallHasOnlyEnglish() {
        val detection = CardLanguageDetector.explain(
            ocrName = "Druido del Suolo Avvizzito",
            englishName = "Blightsoil Druid",
            printed = emptyMap(),
        )
        assertEquals(CardLanguage.ITALIAN, detection.language)
        assertTrue(detection.evidence.contains("title=it"))
        assertTrue(detection.evidence.contains("not-en"))
        assertTrue(detection.evidence.contains("del"))
        assertTrue(detection.evidence.contains("zz"))
        assertEquals(
            "lang: Italiano evidence=${detection.evidence}",
            detection.logLine(),
        )
        val stamped = CardLanguageDetector.applyCopyLanguage(
            com.scantrader.app.data.model.IdentifiedCard(
                game = com.scantrader.app.data.model.Game.MTG,
                name = "Blightsoil Druid",
                setCode = "mor",
                setName = "Morningtide",
                collectorNumber = "59",
                scryfallId = "english-mor-59",
                catalogName = "Blightsoil Druid",
                catalogLang = "en",
                priceEur = "0.40",
            ),
            "Druido del Suolo Avvizzito",
        )
        assertEquals("it", stamped.printedLang)
        assertEquals("Blightsoil Druid", stamped.catalogName)
        assertEquals("Blightsoil Druid", stamped.name)
        assertEquals("english-mor-59", stamped.scryfallId)
        assertEquals("en", stamped.catalogLang)
        assertEquals("0.40", stamped.priceEur)
    }

    @Test
    fun delAloneDoesNotForceItalianAndDiacriticsStillCount() {
        val onlyDel = CardLanguageDetector.explain(
            ocrName = "Maestro del Sol",
            englishName = "Sun Master",
            printed = emptyMap(),
        )
        assertNull(onlyDel.language)

        val french = CardLanguageDetector.explain(
            ocrName = "Épée du Feu",
            englishName = "Fire Sword",
            printed = emptyMap(),
        )
        assertEquals(CardLanguage.FRENCH, french.language)
        assertTrue(french.evidence.contains("title=fr"))
        assertTrue(french.evidence.contains("accent"))

        val spanish = CardLanguageDetector.explain(
            ocrName = "Montaña del Sol",
            englishName = "Sun Mountain",
            printed = emptyMap(),
        )
        assertEquals(CardLanguage.SPANISH, spanish.language)
        assertTrue(spanish.evidence.contains("title=es"))
    }

    @Test
    fun englishOracleTitleStaysEnglish() {
        val detection = CardLanguageDetector.explain(
            ocrName = "Blightsoil Druid",
            englishName = "Blightsoil Druid",
            printed = emptyMap(),
        )
        assertEquals(CardLanguage.ENGLISH, detection.language)
        assertTrue(detection.evidence.contains("name=en"))
    }

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
    fun italianMachopBaseSetOcrIsItalianEvenThoughTheNameIsShared() {
        val shared = mapOf(CardLanguage.ITALIAN to "Machop")
        val fromBothCatalogs = CardLanguageDetector.explain(
            ocrName = "Machop",
            englishName = "Machop",
            printed = shared,
            ocrText = MACHOP_OCR_LOG,
            pokemonSignals = true,
        )
        assertEquals(CardLanguage.ITALIAN, fromBothCatalogs.language)
        assertTrue(fromBothCatalogs.evidence.contains("debolezza"))
        assertTrue(fromBothCatalogs.evidence.contains("resistenza"))
        assertTrue(fromBothCatalogs.evidence.contains("costo di ritirata"))
        assertTrue(fromBothCatalogs.evidence.contains("pv"))
        assertTrue(fromBothCatalogs.evidence.contains("lunghezza"))
        assertTrue(fromBothCatalogs.evidence.contains("peso"))
        assertTrue(fromBothCatalogs.evidence.contains("pokemon base"))
        assertTrue(fromBothCatalogs.evidence.contains("name=shared"))
        assertTrue(fromBothCatalogs.logLine().startsWith("lang: Italiano evidence="))

        val englishOnly = CardLanguageDetector.detect(
            ocrName = "Machop",
            englishName = "Machop",
            printed = emptyMap(),
            ocrText = MACHOP_OCR_LOG,
            pokemonSignals = true,
        )
        assertEquals(CardLanguage.ITALIAN, englishOnly)
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

    @Test
    fun supporterAiutoIsItalianWhenTheTitleDoesNotMatch() {
        val detection = CardLanguageDetector.explain(
            ocrName = "AIUtO",
            englishName = "Team Rocket's Petrel",
            printed = mapOf(CardLanguage.ITALIAN to "Maxus del Team Rocket"),
            ocrText = "AIUtO\nMaxus del Team Rocket",
            pokemonSignals = true,
        )
        assertEquals(CardLanguage.ITALIAN, detection.language)
        assertTrue(detection.evidence.contains("aiuto"))
    }

    @Test
    fun maxusDelTeamRocketPrintedNameIsItalian() {
        val detection = CardLanguageDetector.explain(
            ocrName = "Maxus del Team Rocket",
            englishName = "Team Rocket's Petrel",
            printed = mapOf(CardLanguage.ITALIAN to "Maxus del Team Rocket"),
            ocrText = "AIUtO\nMaxus del Team Rocket\nODRI 176/182",
            pokemonSignals = true,
        )
        assertEquals(CardLanguage.ITALIAN, detection.language)
        assertTrue(detection.evidence.contains("name=it"))
    }

    @Test
    fun failedLookupDoesNotReplaceAConfidentLanguage() {
        val error = CardLanguageDetector.retainConfident(
            previous = CardLanguage.ITALIAN,
            incoming = CardLanguageDetector.Detection(null, "error"),
        )
        assertEquals(CardLanguage.ITALIAN, error.language)
        val shared = CardLanguageDetector.retainConfident(
            previous = CardLanguage.ITALIAN,
            incoming = CardLanguageDetector.Detection(null, "name=shared"),
        )
        assertEquals(CardLanguage.ITALIAN, shared.language)
        val updated = CardLanguageDetector.retainConfident(
            previous = CardLanguage.ITALIAN,
            incoming = CardLanguageDetector.Detection(CardLanguage.FRENCH, "name=fr"),
        )
        assertEquals(CardLanguage.FRENCH, updated.language)
        val none = CardLanguageDetector.retainConfident(
            previous = null,
            incoming = CardLanguageDetector.Detection(null, "error"),
        )
        assertNull(none.language)
    }

    private fun pokemon(text: String): CardLanguage? = CardLanguageDetector.detect(
        ocrName = null,
        englishName = null,
        printed = emptyMap(),
        ocrText = text,
        pokemonSignals = true,
    )

    private companion object {
        /** Device log scan_log6: Italian Base Machop 52/102. `deboleIza` is the OCR of debolezza. */
        val MACHOP_OCR_LOG = """
            Pokémon Base
            Machop
            50 PV
            Pokémon superpotenza. Lunghezza: 0,8 m; Peso: 19,5 kg.
            deboleIza
            Colpo basso
            resistenza
            costo di ritirata
            S allene continuamente per sviluppare al mass
            SI allena continuamente per
            52/102
        """.trimIndent()

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
