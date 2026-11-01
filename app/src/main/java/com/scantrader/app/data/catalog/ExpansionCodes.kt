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
        "swsh1" to "ssh",
        "swsh2" to "rcl",
        "swsh3" to "daa",
        "swsh3.5" to "cpa",
        "swsh4" to "viv",
        "swsh4.5" to "shf",
        "swsh5" to "bst",
        "swsh6" to "cre",
        "swsh7" to "evs",
        "swsh8" to "fst",
        "swsh9" to "brs",
        "swsh10" to "asr",
        "swsh10.5" to "pgo",
        "swsh11" to "lor",
        "swsh12" to "sit",
        "swsh12.5" to "crz",
        "sm1" to "sum",
        "sm2" to "gri",
        "sm3" to "bus",
        "sm3.5" to "slg",
        "sm4" to "cin",
        "sm5" to "upr",
        "sm6" to "fli",
        "sm7" to "ces",
        "sm7.5" to "drm",
        "sm8" to "lot",
        "sm9" to "teu",
        "sm10" to "unb",
        "sm11" to "unm",
        "sm115" to "hif",
        "sm12" to "cec",
        "det1" to "det",
        "xy1" to "xy",
        "xy2" to "flf",
        "xy3" to "ffi",
        "xy4" to "phf",
        "xy5" to "prc",
        "dc1" to "dcr",
        "xy6" to "ros",
        "xy7" to "aor",
        "xy8" to "bkt",
        "xy9" to "bkp",
        "g1" to "gen",
        "xy10" to "fco",
        "xy11" to "sts",
        "xy12" to "evo",
    )

    fun mtg(scryfallSet: String): String =
        scryfallToCardTrader[scryfallSet.lowercase()] ?: scryfallSet.lowercase()

    fun pokemon(tcgdexSet: String): String {
        if (tcgdexSet.any { it.isUpperCase() }) return tcgdexSet.lowercase()
        return tcgdexToCardTrader[tcgdexSet] ?: tcgdexSet.lowercase()
    }
}
