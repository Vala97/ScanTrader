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
    val graded: Boolean = false,
    val signed: Boolean = false,
    val altered: Boolean = false,
    val onVacation: Boolean = false,
    val quantity: Int = 1,
)

data class PriceQuote(
    val amount: String,
    val currency: String,
    val source: String,
)

/**
 * Cheapest marketplace listing for one language, condition, and finish.
 * A missing foil flag is a normal copy. Another language is never mixed in:
 * a €10,000 listing must not move the price of a card that sells at €0.11.
 */
object ListingAverage {
    fun best(
        listings: List<MarketListing>,
        language: String?,
        condition: String?,
        foil: Boolean?,
    ): PriceQuote? {
        val matched = listings.filter { listing ->
            listing.cents > 0 &&
                listing.currency.isNotBlank() &&
                listing.quantity > 0 &&
                matches(listing.language, language) &&
                matches(listing.condition, condition) &&
                foilMatches(listing.foil, foil)
        }
        if (matched.isEmpty()) return null
        val clean = matched.filter { !it.graded && !it.signed && !it.altered && !it.onVacation }
        val used = clean.ifEmpty { matched }
        val currency = used.groupingBy { it.currency.uppercase() }.eachCount().maxBy { it.value }.key
        val cheapest = used.filter { it.currency.equals(currency, ignoreCase = true) }.minBy { it.cents }
        val amount = BigDecimal(cheapest.cents)
            .divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
            .toPlainString()
        return PriceQuote(amount = amount, currency = currency, source = "CardTrader")
    }

    /** Drop a price that belonged to another language or condition. */
    fun unavailable(card: IdentifiedCard): IdentifiedCard = card.copy(
        priceEur = null,
        priceEurFoil = null,
        priceCurrency = null,
        priceSource = null,
    )

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

    /** One Piece listings have no foil field. That is a non-foil copy. */
    private fun foilMatches(actual: Boolean?, wanted: Boolean?): Boolean {
        if (wanted == null) return true
        if (actual == null) return !wanted
        return actual == wanted
    }

    private fun positive(raw: String?): String? {
        val parsed = raw?.trim()?.replace(',', '.')?.toBigDecimalOrNull() ?: return null
        if (parsed <= BigDecimal.ZERO) return null
        return parsed.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }
}
