package com.scantrader.app.data.catalog

/**
 * CardTrader expansion codes sometimes differ from Scryfall / TCGdex.
 * Unknown codes pass through unchanged; add pairs here after a failed import.
 */
object ExpansionCodes {
    private val scryfallToCardTrader = mapOf(
        "plst" to "plst",
        "mb1" to "mb1",
    )

    private val tcgdexToCardTrader = mapOf(
        "base1" to "base1",
        "sv3" to "sv3",
    )

    fun mtg(scryfallSet: String): String =
        scryfallToCardTrader[scryfallSet.lowercase()] ?: scryfallSet.lowercase()

    fun pokemon(tcgdexSet: String): String =
        tcgdexToCardTrader[tcgdexSet.lowercase()] ?: tcgdexSet.lowercase()
}
