package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.IdentifiedCard
import java.math.BigDecimal
import java.math.RoundingMode

data class MarketListing(
    val cents: Int,
    val currency: String,
    val language: String? = null,
    val condition: String? = null,
    val foil: Boolean? = null,
)

data class PriceQuote(
    val amount: String,
    val currency: String,
    val source: String,
)

/**
 * Average of marketplace listings. Prefer rows that match language, condition
 * and foil together. If that filter is empty, average every positive listing.
 */
object ListingAverage {
    fun average(
        listings: List<MarketListing>,
        language: String?,
        condition: String?,
        foil: Boolean?,
    ): PriceQuote? {
        val positive = listings.filter { it.cents > 0 && it.currency.isNotBlank() }
        if (positive.isEmpty()) return null
        val matched = positive.filter { listing ->
            matches(listing.language, language) &&
                matches(listing.condition, condition) &&
                (foil == null || listing.foil == foil)
        }
        val used = matched.ifEmpty { positive }
        val currency = used.groupingBy { it.currency.uppercase() }.eachCount().maxBy { it.value }.key
        val same = used.filter { it.currency.equals(currency, ignoreCase = true) }
        val amount = BigDecimal(same.sumOf { it.cents })
            .divide(BigDecimal(same.size * 100L), 2, RoundingMode.HALF_UP)
            .toPlainString()
        return PriceQuote(amount = amount, currency = currency, source = "CardTrader")
    }

    /** Scryfall EUR, then USD, for the foil flag. Null when both are missing. */
    fun fromScryfall(
        eur: String?,
        eurFoil: String?,
        usd: String?,
        usdFoil: String?,
        foil: Boolean,
    ): PriceQuote? {
        val eurValue = positive(if (foil) eurFoil ?: eur else eur)
        if (eurValue != null) return PriceQuote(eurValue, "EUR", "Scryfall")
        val usdValue = positive(if (foil) usdFoil ?: usd else usd)
        if (usdValue != null) return PriceQuote(usdValue, "USD", "Scryfall")
        return null
    }

    fun display(
        eur: String?,
        eurFoil: String?,
        usd: String?,
        usdFoil: String?,
        currency: String?,
        source: String?,
        foil: Boolean,
    ): String? {
        val quote = when (currency?.uppercase()) {
            "USD" -> fromScryfall(null, null, usd, usdFoil, foil)?.copy(source = source ?: "Scryfall")
            else -> {
                val eurQuote = fromScryfall(eur, eurFoil, null, null, foil)
                eurQuote?.copy(source = source ?: eurQuote.source)
                    ?: fromScryfall(null, null, usd, usdFoil, foil)?.copy(source = source ?: "Scryfall")
            }
        } ?: return null
        val symbol = if (quote.currency == "USD") "$" else "€"
        val via = quote.source.let { " · $it" }
        return "$symbol${quote.amount}$via"
    }

    fun apply(card: IdentifiedCard, quote: PriceQuote, foil: Boolean): IdentifiedCard {
        val eur = quote.amount.takeIf { quote.currency.equals("EUR", ignoreCase = true) }
        val usd = quote.amount.takeIf { quote.currency.equals("USD", ignoreCase = true) }
        return card.copy(
            priceEur = if (foil) card.priceEur else eur ?: card.priceEur,
            priceEurFoil = if (foil) eur ?: card.priceEurFoil else card.priceEurFoil,
            priceUsd = if (foil) card.priceUsd else usd ?: card.priceUsd,
            priceUsdFoil = if (foil) usd ?: card.priceUsdFoil else card.priceUsdFoil,
            priceCurrency = quote.currency,
            priceSource = quote.source,
        )
    }

    private fun matches(actual: String?, wanted: String?): Boolean {
        if (wanted.isNullOrBlank()) return true
        return actual.equals(wanted, ignoreCase = true)
    }

    private fun positive(raw: String?): String? {
        val parsed = raw?.trim()?.replace(',', '.')?.toBigDecimalOrNull() ?: return null
        if (parsed <= BigDecimal.ZERO) return null
        return parsed.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }
}
