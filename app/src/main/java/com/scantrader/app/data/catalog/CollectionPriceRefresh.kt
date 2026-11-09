package com.scantrader.app.data.catalog

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.MarketRefresh
import com.scantrader.app.data.model.IdentifiedCard
import kotlinx.coroutines.yield

/**
 * Re-quotes every row of one collection. Rows of the same print share one fetch.
 * A failed fetch leaves that row's stored prices alone.
 */
object CollectionPriceRefresh {
    data class Outcome(
        val stored: Int,
        val failed: Int,
        val total: Int,
    ) {
        val message: String
            get() = when {
                total == 0 -> "Nessuna carta"
                failed == 0 && stored == 0 -> "Nessun prezzo di mercato da aggiornare"
                failed == 0 -> "Prezzi aggiornati"
                stored == 0 -> "Nessun prezzo di mercato trovato. I prezzi precedenti restano."
                else -> "Aggiornati $stored. $failed senza prezzo di mercato."
            }
    }

    suspend fun run(
        cards: List<CardEntryEntity>,
        quote: suspend (CardEntryEntity) -> IdentifiedCard,
        save: suspend (CardEntryEntity) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Outcome {
        val total = cards.size
        if (total == 0) return Outcome(stored = 0, failed = 0, total = 0)
        var stored = 0
        var failed = 0
        var done = 0
        for (group in cards.groupBy { MarketRefresh.quoteKey(it) }.values) {
            val sample = group.first()
            val attempt = MarketRefresh.canQuote(sample.game)
            val priced = if (!attempt) {
                null
            } else {
                runCatching { quote(sample) }.getOrNull()
            }
            for (row in group) {
                if (attempt) {
                    if (priced == null) {
                        failed++
                    } else {
                        val decision = MarketRefresh.applyQuote(row, priced)
                        if (decision.save) save(decision.entry)
                        if (decision.priced) stored++ else failed++
                    }
                }
                done++
                onProgress(done, total)
            }
            yield()
        }
        return Outcome(stored = stored, failed = failed, total = total)
    }
}
