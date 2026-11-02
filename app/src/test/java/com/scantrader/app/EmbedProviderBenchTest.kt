package com.scantrader.app

import com.scantrader.app.vision.EmbedProviderBench
import com.scantrader.app.vision.MiloEmbedder
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbedProviderBenchTest {
    @Test
    fun slowerNnapiLosesToXnnpack() {
        assertEquals(EmbedProviderBench.XNNPACK, EmbedProviderBench.choose(nnapiMs = 3300, xnnpackMs = 800))
        assertEquals(EmbedProviderBench.NNAPI, EmbedProviderBench.choose(nnapiMs = 400, xnnpackMs = 900))
        assertEquals(EmbedProviderBench.NNAPI, EmbedProviderBench.choose(nnapiMs = 800, xnnpackMs = 800))
        assertEquals(EmbedProviderBench.XNNPACK, EmbedProviderBench.choose(nnapiMs = null, xnnpackMs = 800))
        assertEquals(EmbedProviderBench.NNAPI, EmbedProviderBench.choose(nnapiMs = 800, xnnpackMs = null))
    }

    @Test
    fun exclusiveProviderDoesNotKeepTheOtherAccelerator() {
        assertEquals(
            listOf(EmbedProviderBench.XNNPACK),
            MiloEmbedder.sessionOrder(
                forceCpuOnly = false,
                nnapi = true,
                preferred = EmbedProviderBench.XNNPACK,
                exclusive = true,
            ),
        )
        assertEquals(
            listOf(EmbedProviderBench.XNNPACK, EmbedProviderBench.NNAPI, "cpu"),
            MiloEmbedder.sessionOrder(
                forceCpuOnly = false,
                nnapi = true,
                preferred = EmbedProviderBench.XNNPACK,
                exclusive = false,
            ),
        )
    }
}
