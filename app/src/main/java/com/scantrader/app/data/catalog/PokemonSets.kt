package com.scantrader.app.data.catalog

/**
 * Printed codes on international cards (DRI, OBF, …) → TCGdex set ids (`sv10`, `sv03`).
 * Japanese and Chinese cards print their own set id (SV2a, CSV3C); those are not remapped.
 */
object PokemonSets {
    private val internationalPrinted = setOf(
        "svi", "pal", "obf", "mew", "par", "paf", "tef", "twm", "sfa", "scr", "ssp", "pre",
        "jtg", "dri", "wht", "blk", "meg", "pfl", "asc", "por", "cri", "pbl",
    )

    private val toTcgdex = mapOf(
        "svi" to "sv01",
        "sv1" to "sv01",
        "sv01" to "sv01",
        "pal" to "sv02",
        "sv2" to "sv02",
        "sv02" to "sv02",
        "obf" to "sv03",
        "sv3" to "sv03",
        "sv03" to "sv03",
        "mew" to "sv03.5",
        "sv3pt5" to "sv03.5",
        "sv03.5" to "sv03.5",
        "par" to "sv04",
        "sv4" to "sv04",
        "sv04" to "sv04",
        "paf" to "sv04.5",
        "sv4pt5" to "sv04.5",
        "sv04.5" to "sv04.5",
        "tef" to "sv05",
        "sv5" to "sv05",
        "sv05" to "sv05",
        "twm" to "sv06",
        "sv6" to "sv06",
        "sv06" to "sv06",
        "sfa" to "sv06.5",
        "sv6pt5" to "sv06.5",
        "sv06.5" to "sv06.5",
        "scr" to "sv07",
        "sv7" to "sv07",
        "sv07" to "sv07",
        "ssp" to "sv08",
        "sv8" to "sv08",
        "sv08" to "sv08",
        "pre" to "sv08.5",
        "sv8pt5" to "sv08.5",
        "sv08.5" to "sv08.5",
        "jtg" to "sv09",
        "sv9" to "sv09",
        "sv09" to "sv09",
        "dri" to "sv10",
        "sv9pt5" to "sv10",
        "sv10" to "sv10",
        "wht" to "sv10.5w",
        "blk" to "sv10.5b",
        "meg" to "me01",
        "me1" to "me01",
        "me01" to "me01",
        "pfl" to "me02",
        "me2" to "me02",
        "me02" to "me02",
        "asc" to "me02.5",
        "me2pt5" to "me02.5",
        "me02.5" to "me02.5",
        "por" to "me03",
        "me3" to "me03",
        "me03" to "me03",
        "cri" to "me04",
        "me4" to "me04",
        "me04" to "me04",
        "pbl" to "me05",
        "me5" to "me05",
        "me05" to "me05",
    )

    fun isInternationalPrinted(code: String?): Boolean =
        code?.trim()?.lowercase() in internationalPrinted

    /** TCGdex ids for a known printed code. Unknown codes are omitted. */
    fun recognizedIds(printedOrId: String?): List<String> {
        val key = normalize(printedOrId) ?: return emptyList()
        return listOfNotNull(toTcgdex[key])
    }

    fun idCandidates(printedOrId: String?): List<String> {
        val known = recognizedIds(printedOrId)
        if (known.isNotEmpty()) return known
        return listOfNotNull(normalize(printedOrId))
    }

    fun sameSet(a: String?, b: String?): Boolean {
        val right = idCandidates(b).toSet()
        if (right.isEmpty()) return false
        return idCandidates(a).any { it in right }
    }

    /**
     * Set ids to try on one TCGdex language.
     * English and Italian use the padded international id. Japanese and Chinese keep the printed id.
     */
    fun idsFor(printedOrId: String?, lang: String): List<String> {
        val raw = printedOrId?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val key = raw.lowercase()
        return when (lang) {
            "en", "it" -> listOfNotNull(toTcgdex[key] ?: key)
            else -> listOf(raw)
        }
    }

    private fun normalize(value: String?): String? =
        value?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
}
