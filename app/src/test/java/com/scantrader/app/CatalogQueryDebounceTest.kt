package com.scantrader.app

import com.scantrader.app.data.catalog.CatalogQueryDebounce
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogQueryDebounceTest {
    @Test
    fun queriesOnceAfterTwoStableFramesAndAgainWhenIdentityChanges() {
        val debounce = CatalogQueryDebounce(stableFramesNeeded = 2)
        assertTrue(!debounce.shouldQuery("pioppo|83|"))
        assertTrue(debounce.shouldQuery("pioppo|83|"))
        assertTrue(!debounce.shouldQuery("pioppo|83|"))
        assertTrue(!debounce.shouldQuery("pioppo|83|dft"))
        assertTrue(debounce.shouldQuery("pioppo|83|dft"))
    }

    @Test
    fun failedQueryCanRunAgainForTheSameIdentity() {
        val debounce = CatalogQueryDebounce(stableFramesNeeded = 2)
        assertTrue(!debounce.shouldQuery("pioppo|83|"))
        assertTrue(debounce.shouldQuery("pioppo|83|"))
        debounce.invalidateLastQuery()
        assertTrue(debounce.shouldQuery("pioppo|83|"))
    }
}
