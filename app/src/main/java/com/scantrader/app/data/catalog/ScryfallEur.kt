package com.scantrader.app.data.catalog

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.network.scryfall.ScryfallCard
import com.scantrader.app.network.scryfall.ScryfallClient
import java.math.BigDecimal
import java.math.RoundingMode

/** One Scryfall printing reduced to the fields the EUR picker needs. */
data class EurPrint(
    val eur: String? = null,
    val eurFoil: String? = null,
    val releasedAt: String? = null,
    val id: String? = null,
)

/**
 * Magic price in euros from Scryfall only.
 *
 * Exact printing (`prices.eur` / `prices.eur_foil`) wins. If that side is missing,
 * the English printing of the same set and collector number is next, then the
 * cheapest paper printing that has the side (most recent `released_at` on a tie).
 * Anything other than the exact printing is labeled [APPROX].
 * A foil request with no `eur_foil` anywhere uses the same chain on non-foil EUR,
 * still labeled [APPROX].
 */
object ScryfallEur {
    const val EXACT = "Scryfall"
    const val APPROX = "Scryfall (circa)"

    fun quote(
        exact: EurPrint,
        english: EurPrint?,
        others: List<EurPrint>,
        foil: Boolean,
    ): PriceQuote? {
        side(exact, foil)?.let { return PriceQuote(it, "EUR", EXACT) }
        val englishPrint = english?.takeUnless { it.id != null && it.id == exact.id }
        englishPrint?.let { print ->
            side(print, foil)?.let { return PriceQuote(it, "EUR", APPROX) }
        }
        cheapestPrint(pool(others, exact, englishPrint), foil)?.let {
            return PriceQuote(it.second, "EUR", APPROX)
        }
        if (!foil) return null
        positive(exact.eur)?.let { return PriceQuote(it, "EUR", APPROX) }
        englishPrint?.let { print ->
            positive(print.eur)?.let { return PriceQuote(it, "EUR", APPROX) }
        }
        cheapestPrint(pool(others, exact, englishPrint), foil = false)?.let {
            return PriceQuote(it.second, "EUR", APPROX)
        }
        return null
    }

    /** Positive euro amount, or null. `0` and blank are missing. */
    fun positive(raw: String?): String? {
        val parsed = raw?.trim()?.replace(',', '.')?.toBigDecimalOrNull() ?: return null
        if (parsed <= BigDecimal.ZERO) return null
        return parsed.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    private fun side(print: EurPrint, foil: Boolean): String? =
        positive(if (foil) print.eurFoil else print.eur)

    private fun pool(others: List<EurPrint>, exact: EurPrint, english: EurPrint?): List<EurPrint> =
        others.filter { print ->
            val id = print.id ?: return@filter true
            id != exact.id && id != english?.id
        }

    /** Lowest amount, then the latest release date (`YYYY-MM-DD` sorts lexicographically). */
    internal fun cheapestPrint(prints: List<EurPrint>, foil: Boolean): Pair<EurPrint, String>? {
        val priced = prints.mapNotNull { print ->
            val amount = side(print, foil) ?: return@mapNotNull null
            print to amount
        }
        if (priced.isEmpty()) return null
        return priced.minWith(
            compareBy<Pair<EurPrint, String>> { it.second.toBigDecimal() }
                .thenByDescending { it.first.releasedAt.orEmpty() },
        )
    }
}

/**
 * Resolves a Magic card to a Scryfall EUR quote. No CardTrader calls.
 * When the card already carries the requested EUR side from Scryfall, no network.
 */
class ScryfallEurPrices(
    private val scryfall: ScryfallClient,
) {
    suspend fun quote(card: IdentifiedCard, foil: Boolean): PriceQuote? {
        if (card.game != Game.MTG) return null
        val stored = ScryfallEur.positive(if (foil) card.priceEurFoil else card.priceEur)
        // An approximate quote must be resolved again: the stored amount may be
        // the other finish, or the English print, and the label has to stay honest.
        if (stored != null && (card.priceSource == null || card.priceSource == ScryfallEur.EXACT)) {
            return PriceQuote(stored, "EUR", ScryfallEur.EXACT)
        }
        val exactCard = card.scryfallId?.let { id ->
            runCatching { scryfall.cardById(id) }.getOrNull()
        }
        val exact = exactCard?.toEurPrint()
            ?: EurPrint(eur = card.priceEur, eurFoil = card.priceEurFoil, id = card.scryfallId)
        val english = if (ScryfallEur.positive(if (foil) exact.eurFoil else exact.eur) == null) {
            englishPrinting(card, exactCard)
        } else {
            null
        }
        val englishPrint = english?.toEurPrint()
        val haveSide = ScryfallEur.positive(if (foil) exact.eurFoil else exact.eur) != null ||
            (englishPrint != null && ScryfallEur.positive(if (foil) englishPrint.eurFoil else englishPrint.eur) != null)
        val others = if (!haveSide) otherPrints(exactCard?.oracleId) else emptyList()
        return ScryfallEur.quote(exact, englishPrint, others, foil)
    }

    private suspend fun englishPrinting(card: IdentifiedCard, exact: ScryfallCard?): ScryfallCard? {
        if (exact?.lang.equals("en", ignoreCase = true)) return null
        val set = card.setCode.ifBlank { exact?.set.orEmpty() }
        val number = card.collectorNumber.ifBlank { exact?.collectorNumber.orEmpty() }
        if (set.isBlank() || number.isBlank()) return null
        return runCatching { scryfall.cardBySetNumberLang(set, number, "en") }.getOrNull()
            ?: runCatching { scryfall.cardBySetAndNumber(set, number) }.getOrNull()
    }

    private suspend fun otherPrints(oracleId: String?): List<EurPrint> {
        if (oracleId.isNullOrBlank()) return emptyList()
        val response = runCatching {
            scryfall.search("oracleid:$oracleId game:paper", unique = "prints")
        }.getOrNull() ?: return emptyList()
        return response.data.map { it.toEurPrint() }
    }

    private fun ScryfallCard.toEurPrint(): EurPrint = EurPrint(
        eur = prices?.eur,
        eurFoil = prices?.eurFoil,
        releasedAt = releasedAt,
        id = id,
    )
}
