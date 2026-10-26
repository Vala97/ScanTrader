package com.scantrader.app.scan

/**
 * Stable finish/variant token for one catalog object.
 * Foil and non-foil of the same printing share Scryfall's `finishes` list, so
 * they stay one token. Etched, showcase, and promo printings do not.
 */
object CardFinish {
    fun from(
        finishes: List<String>?,
        frameEffects: List<String>?,
        promoTypes: List<String>?,
    ): String? {
        val parts = ArrayList<String>(3)
        finishes.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()
            .takeIf { it.isNotEmpty() }
            ?.let { parts += it.joinToString("+") }
        frameEffects.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()
            .takeIf { it.isNotEmpty() }
            ?.let { parts += "frame:" + it.joinToString("+") }
        promoTypes.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()
            .takeIf { it.isNotEmpty() }
            ?.let { parts += "promo:" + it.joinToString("+") }
        return parts.joinToString("|").ifBlank { null }
    }
}
