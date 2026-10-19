package com.scantrader.app.data.catalog

/**
 * Maps abbreviations printed on modern Pokémon cards (bottom-left) to TCGdex set ids.
 * Unknown codes pass through unchanged.
 */
object PokemonSets {
    private val abbrToTcgdex = mapOf(
        "svi" to "sv01",
        "pal" to "sv02",
        "obf" to "sv03",
        "mew" to "sv03.5",
        "par" to "sv04",
        "paf" to "sv04.5",
        "tef" to "sv05",
        "twm" to "sv06",
        "scr" to "sv07",
        "ssp" to "sv08",
        "pre" to "sv08.5",
        "jtg" to "sv09",
        "dri" to "sv10",
        "sv1" to "sv01",
        "sv2" to "sv02",
        "sv3" to "sv03",
        "sv4" to "sv04",
        "sv5" to "sv05",
        "sv6" to "sv06",
        "sv7" to "sv07",
        "sv8" to "sv08",
        "sv9" to "sv09",
        "sv10" to "sv10",
    )

    fun tcgdexSetId(printedOrId: String?): String? {
        val raw = printedOrId?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return abbrToTcgdex[raw] ?: raw
    }

    fun idCandidates(printedOrId: String?): List<String> {
        val raw = printedOrId?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOfNotNull(abbrToTcgdex[raw], raw).distinct()
    }
}
