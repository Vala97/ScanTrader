package com.scantrader.app

import com.scantrader.app.data.catalog.CatalogNetwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogNetworkTest {
    @Test
    fun slowCallGivesUp() = runBlocking {
        val result = CatalogNetwork.awaitOrNull(timeoutMs = 40) {
            delay(500)
            "remote"
        }
        assertNull(result)
    }

    @Test
    fun fastCallReturnsTheRemoteValue() = runBlocking {
        val result = CatalogNetwork.awaitOrNull(timeoutMs = 500) { "local-not-used" }
        assertEquals("local-not-used", result)
    }
}
