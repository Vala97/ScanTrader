package com.scantrader.app.data.catalog

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.MarketRefresh
import kotlinx.coroutines.yield

/**
 * Re-quotes every row of one collection. Rows of the same print share one fetch.
 * A failed fetch leaves that row's stored prices alone.
 * Games without a market lookup are counted apart from failed quotes.
 */
object CollectionPriceRefresh {
    data class Outcome(
        val stored: Int,
        val failed: Int,
        val unsupported: Int,
        val total: Int,
        val needsToken: Boolean,
    ) {
        val message: String
            get() = when {
                total == 0 -> "Nessuna carta"
                needsToken && stored == 0 && failed > 0 && failed + unsupported == total ->
                    "Serve il token CardTrader in Impostazioni. I prezzi precedenti restano."
                needsToken && stored > 0 ->
                    "Aggiornati $stored. One Piece e Riftbound richiedono il token CardTrader."
                failed == 0 && stored == 0 -> "Nessun prezzo di mercato da aggiornare"
                failed == 0 && unsupported > 0 -> "Prezzi aggiornati. $unsupported senza listino."
                failed == 0 -> "Prezzi aggiornati"
                stored == 0 -> "Nessun prezzo di mercato trovato. I prezzi precedenti restano."
                else -> "Aggiornati $stored. $failed senza prezzo di mercato."
            }
    }

    suspend fun run(
        cards: List<CardEntryEntity>,
        quote: suspend (CardEntryEntity) -> InventoryMarket.Result,
        save: suspend (CardEntryEntity) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Outcome {
        val total = cards.size
        if (total == 0) return Outcome(stored = 0, failed = 0, unsupported = 0, total = 0, needsToken = false)
        var stored = 0
        var failed = 0
        var unsupported = 0
        var needsToken = false
        var done = 0
        for (group in cards.groupBy { MarketRefresh.quoteKey(it) }.values) {
            val sample = group.first()
            val result = if (!MarketRefresh.canQuote(sample.game)) {
                null
            } else {
                runCatching { quote(sample) }.getOrElse { InventoryMarket.Result.Unavailable }
            }
            for (row in group) {
                if (result == null) {
                    unsupported++
                } else {
                    when (result) {
                        InventoryMarket.Result.NeedsToken -> {
                            failed++
                            needsToken = true
                        }
                        InventoryMarket.Result.Unavailable -> failed++
                        is InventoryMarket.Result.Priced -> {
                            val decision = MarketRefresh.applyQuote(row, result.card)
                            if (decision.save) save(decision.entry)
                            if (decision.priced) stored++ else failed++
                        }
                    }
                }
                done++
                onProgress(done, total)
            }
            yield()
        }
        return Outcome(
            stored = stored,
            failed = failed,
            unsupported = unsupported,
            total = total,
            needsToken = needsToken,
        )
    }
}
