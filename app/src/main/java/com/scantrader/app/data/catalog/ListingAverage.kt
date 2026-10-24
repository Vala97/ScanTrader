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

    /**
     * Euro amount already stored on the card. USD is never shown.
     * A foil line with no `eur_foil` uses the non-foil EUR of the same card.
     */
    fun display(
        eur: String?,
        eurFoil: String?,
        source: String?,
        foil: Boolean,
    ): String? {
        val amount = positive(if (foil) eurFoil ?: eur else eur) ?: return null
        val via = source?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        return "€$amount$via"
    }

    fun apply(card: IdentifiedCard, quote: PriceQuote, foil: Boolean): IdentifiedCard {
        if (!quote.currency.equals("EUR", ignoreCase = true)) return card
        val eur = quote.amount
        return card.copy(
            priceEur = if (foil) card.priceEur else eur,
            priceEurFoil = if (foil) eur else card.priceEurFoil,
            priceCurrency = "EUR",
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
