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
}
