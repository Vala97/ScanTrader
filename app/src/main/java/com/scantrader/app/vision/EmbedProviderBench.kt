package com.scantrader.app.vision

/**
 * First launch times one embed on NNAPI and one on XNNPACK/CPU.
 * The faster provider is kept. Equal times stay on NNAPI.
 */
object EmbedProviderBench {
    const val NNAPI = "nnapi+xnnpack"
    const val XNNPACK = "xnnpack"

    fun choose(nnapiMs: Long?, xnnpackMs: Long?): String = when {
        nnapiMs == null -> XNNPACK
        xnnpackMs == null -> NNAPI
        xnnpackMs < nnapiMs -> XNNPACK
        else -> NNAPI
    }
}
