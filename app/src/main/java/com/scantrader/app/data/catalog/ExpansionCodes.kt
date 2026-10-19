package com.scantrader.app.data.catalog

/**
 * CardTrader expansion codes sometimes differ from Scryfall / TCGdex set ids.
 * Japanese and Chinese ids keep their original case (`SV2a`) and are not remapped:
 * lowercasing them would collide with the international id (`sv10` → dri).
 */
object ExpansionCodes {
    private val scryfallToCardTrader = mapOf(
        "plst" to "plst",
        "mb1" to "mb1",
    )

    /** TCGdex international set id → CardTrader expansion code. */
    private val tcgdexToCardTrader = mapOf(
        "sv01" to "sv1",
        "sv02" to "pal",
        "sv03" to "obf",
        "sv03.5" to "151",
        "sv04" to "par",
        "sv04.5" to "paf",
        "sv05" to "tef",
        "sv06" to "twm",
        "sv06.5" to "sfa",
        "sv07" to "scr",
        "sv08" to "ssp",
        "sv08.5" to "pre",
        "sv09" to "jtg",
        "sv10" to "dri",
        "sv3" to "obf",
        "sv3pt5" to "151",
        "me01" to "meg",
        "me02" to "pfl",
        "me02.5" to "asc",
        "me03" to "por",
        "me04" to "cri",
        "me05" to "pbl",
        "base1" to "base1",
    )

    fun mtg(scryfallSet: String): String =
        scryfallToCardTrader[scryfallSet.lowercase()] ?: scryfallSet.lowercase()

    fun pokemon(tcgdexSet: String): String {
        if (tcgdexSet.any { it.isUpperCase() }) return tcgdexSet.lowercase()
        return tcgdexToCardTrader[tcgdexSet] ?: tcgdexSet.lowercase()
    }
}
