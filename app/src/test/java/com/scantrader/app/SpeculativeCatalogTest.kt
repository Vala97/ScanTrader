package com.scantrader.app

import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.SpeculativeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeculativeCatalogTest {
    @Test
    fun firstConfidentFrameStartsAndTheNextAgreementAccepts() {
        val catalog = SpeculativeCatalog()
        val first = catalog.onOcr(ParsedCardText("Machop", "52", "wird", 102))
        assertTrue(first.start)
        assertFalse(first.accept)
        assertEquals("p:52/102", first.key)

        val second = catalog.onOcr(ParsedCardText("Machop", "52", "base", 102))
        assertFalse(second.start)
        assertTrue(second.accept)
        assertEquals("p:52/102", second.key)
    }

    @Test
    fun aDifferentCardRestartsInsteadOfAccepting() {
        val catalog = SpeculativeCatalog()
        catalog.onOcr(ParsedCardText("Machop", "52", "wird", 102))
        val other = catalog.onOcr(ParsedCardText("Kadabra", "48", "base", 102))
        assertTrue(other.start)
        assertFalse(other.accept)
        assertEquals("p:48/102", other.key)

        val switched = catalog.onOcr(ParsedCardText("Abra", "43", "base", 102))
        assertTrue(switched.start)
        assertFalse(switched.accept)
    }

    @Test
    fun aiutoOrAMissingCollectorIsNotConfident() {
        assertNull(SpeculativeCatalog.confidentKey(ParsedCardText("Aiuto", "176", "dri", 182)))
        assertNull(SpeculativeCatalog.confidentKey(ParsedCardText("Machop", null, "wird", 102)))
        assertNull(SpeculativeCatalog.confidentKey(ParsedCardText("FASE", "52", "wird", 102)))
        val catalog = SpeculativeCatalog()
        val step = catalog.onOcr(ParsedCardText("Aiuto", null, null, null))
        assertFalse(step.start)
        assertFalse(step.accept)
        assertNull(step.key)
    }
}
