package com.scantrader.app

import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.ui.confirm.LanguageDetectStatus
import com.scantrader.app.ui.confirm.LanguageDetectUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageDetectUiTest {
    @Test
    fun mapsDetectionToUiStates() {
        assertEquals(LanguageDetectStatus.Analyzing, LanguageDetectUi(LanguageDetectStatus.Analyzing).status)
        val hit = LanguageDetectUi.fromDetection(CardLanguage.ITALIAN)
        assertEquals(LanguageDetectStatus.Recognized, hit.status)
        assertEquals(CardLanguage.ITALIAN, hit.language)
        assertEquals("Lingua: riconosciuta · Italiano", hit.label())
        val miss = LanguageDetectUi.fromDetection(null)
        assertEquals(LanguageDetectStatus.NotFound, miss.status)
        assertNull(miss.language)
        assertEquals("Lingua: non trovata", miss.label())
        assertEquals("Lingua: in analisi…", LanguageDetectUi(LanguageDetectStatus.Analyzing).label())
    }
}
