package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.network.tcgdex.TcgdexCard
import java.util.Locale

/**
 * Pokémon market EUR is the TCGdex Cardmarket average, or the trend when the average is missing.
 * Holo amounts are not a price: the app has no foil line for Pokémon.
 * A saved TCGdex id is that print. Without one, only a single set-and-number id is used.
 */
object PokemonMarket {
    fun price(card: TcgdexCard): PriceQuote? {
        val market = card.pricing?.cardmarket ?: return null
        val value = market.avg ?: market.trend
        if (value == null || value <= 0.0) return null
        return PriceQuote(
            amount = String.format(Locale.US, "%.2f", value),
            currency = "EUR",
            source = "Cardmarket",
        )
    }

    /**
     * Western languages share a TCGdex id, so English is a second read of the same print
     * when the saved language has no Cardmarket amount. Japanese and Chinese catalogs do not.
     */
    fun languagesToTry(language: CardLanguage): List<String> = when (language) {
        CardLanguage.JAPANESE -> listOf("ja")
        CardLanguage.CHINESE_S -> listOf("zh-cn")
        CardLanguage.CHINESE_T -> listOf("zh-tw")
        CardLanguage.KOREAN -> listOf("en")
        CardLanguage.ENGLISH -> listOf("en")
        else -> listOf(language.westernTcgdex(), "en")
    }

    suspend fun quote(
        tcgdexId: String?,
        setCode: String,
        collectorNumber: String,
        language: CardLanguage,
        load: suspend (lang: String, id: String) -> TcgdexCard,
    ): PriceQuote? {
        val stored = tcgdexId?.trim()?.takeIf { it.isNotEmpty() }
        val id = stored ?: resolveId(setCode, collectorNumber, language, load) ?: return null
        for (lang in languagesToTry(language)) {
            val card = runCatching { load(lang, id) }.getOrNull() ?: continue
            if (!samePrint(card, id, setCode, collectorNumber, stored != null)) continue
            price(card)?.let { return it }
        }
        return null
    }

    private suspend fun resolveId(
        setCode: String,
        collectorNumber: String,
        language: CardLanguage,
        load: suspend (lang: String, id: String) -> TcgdexCard,
    ): String? {
        val set = setCode.trim()
        val number = collectorNumber.trim()
        if (set.isEmpty() || number.isEmpty()) return null
        val found = mutableListOf<String>()
        for (candidate in PokemonCatalog.cardIds(set, number)) {
            for (lang in languagesToTry(language)) {
                val card = runCatching { load(lang, candidate) }.getOrNull() ?: continue
                if (!samePrint(card, candidate, set, number, storedId = false)) continue
                found += card.id.ifBlank { candidate }
                break
            }
        }
        return found.map { it.lowercase(Locale.ROOT) }.distinct().singleOrNull()
    }

    private fun samePrint(
        card: TcgdexCard,
        requestedId: String,
        setCode: String,
        collectorNumber: String,
        storedId: Boolean,
    ): Boolean {
        val setId = card.set?.id?.takeIf { it.isNotBlank() } ?: card.id.substringBefore('-', "")
        if (setCode.isNotBlank() && setId.isNotBlank() && !setId.equals(setCode.trim(), ignoreCase = true)) {
            return false
        }
        if (card.id.equals(requestedId, ignoreCase = true)) return true
        if (storedId) return false
        val local = card.localId.ifBlank { card.id.substringAfter('-', "") }
        return setId.equals(setCode.trim(), ignoreCase = true) &&
            PokemonCatalog.numberMatches(collectorNumber, local)
    }

    private fun CardLanguage.westernTcgdex(): String = when (this) {
        CardLanguage.ITALIAN -> "it"
        CardLanguage.FRENCH -> "fr"
        CardLanguage.GERMAN -> "de"
        CardLanguage.SPANISH -> "es"
        CardLanguage.PORTUGUESE -> "pt"
        else -> "en"
    }
}
