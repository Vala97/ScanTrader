package com.scantrader.app

import com.scantrader.app.scan.FullCardOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FullCardOcrTest {
    @Test
    fun titleRetryDoesNotReplaceTheFullCard() {
        val full = "© 1993-2006 Wizards of the Coast 17/150\nMeadowboon\nCreature"
        val title = "Meadowboon"
        val text = FullCardOcr.text(full, title)
        assertEquals(full, text)
        assertNotEquals(title.length, text.length)
    }

    @Test
    fun emptyFullReadStaysEmpty() {
        assertEquals("", FullCardOcr.text("", "Meadowboon"))
    }
}
