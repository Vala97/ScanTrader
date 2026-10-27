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

    /**
     * Finish token with foil / non-foil removed. Foil stays a separate flag.
     * `foil+nonfoil` is null. `etched+foil|frame:showcase` is `etched|frame:showcase`.
     */
    fun variantOf(finish: String?): String? {
        if (finish.isNullOrBlank()) return null
        val parts = finish.split('|').mapNotNull { part ->
            val trimmed = part.trim().lowercase()
            if (trimmed.isEmpty()) return@mapNotNull null
            if (trimmed.startsWith("frame:") || trimmed.startsWith("promo:")) {
                trimmed.takeIf { it.length > trimmed.substringBefore(':').length + 1 }
            } else {
                trimmed.split('+')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != "foil" && it != "nonfoil" }
                    .distinct()
                    .sorted()
                    .joinToString("+")
                    .takeIf { it.isNotEmpty() }
            }
        }
        return parts.joinToString("|").ifBlank { null }
    }
}
