package com.scantrader.app.data.export

import com.scantrader.app.data.catalog.ExpansionCodes
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.model.Game
import java.util.Locale

object CardTraderCsv {
    fun mtgHeader(): List<String> = listOf(
        "scryfall_id",
        "name",
        "expansion_code",
        "collector_number",
        "quantity",
        "language",
        "condition",
        "foil",
        "price",
    )

    fun pokemonHeader(): List<String> = listOf(
        "name",
        "expansion_code",
        "collector_number",
        "quantity",
        "language",
        "condition",
        "foil",
        "tcgplayer_id",
        "price",
    )

    fun build(entries: List<CardEntryEntity>, game: Game): String {
        val header = if (game == Game.MTG) mtgHeader() else pokemonHeader()
        val rows = entries.filter { it.game == game }.map { row(it, game) }
        return (listOf(header) + rows).joinToString("\n") { csvLine(it) } + "\n"
    }

    private fun row(entry: CardEntryEntity, game: Game): List<String> {
        val foil = if (entry.foil) "true" else "false"
        val price = exportPrice(entry).orEmpty()
        return if (game == Game.MTG) {
            listOf(
                entry.scryfallId.orEmpty(),
                entry.name,
                ExpansionCodes.mtg(entry.setCode),
                entry.collectorNumber,
                entry.quantity.toString(),
                entry.language,
                entry.condition,
                foil,
                price,
            )
        } else {
            listOf(
                entry.name,
                ExpansionCodes.pokemon(entry.setCode),
                entry.collectorNumber,
                entry.quantity.toString(),
                entry.language,
                entry.condition,
                foil,
                entry.tcgplayerId.orEmpty(),
                price,
            )
        }
    }

    /** CardTrader accepts `price` in the seller account currency (EUR for EU). */
    fun exportPrice(entry: CardEntryEntity): String? {
        val raw = if (entry.foil) {
            entry.priceEurFoil?.takeIf { it.isNotBlank() } ?: entry.priceEur
        } else {
            entry.priceEur
        }?.takeIf { it.isNotBlank() } ?: return null
        return normalizePrice(raw)
    }

    fun normalizePrice(raw: String): String? {
        val parsed = raw.replace(',', '.').toDoubleOrNull() ?: return null
        if (parsed <= 0.0) return null
        return String.format(Locale.US, "%.2f", parsed)
    }

    fun csvLine(columns: List<String>): String =
        columns.joinToString(",") { escape(it) }

    fun escape(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    fun filename(collectionName: String, game: Game): String {
        val slug = collectionName.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
        return "${slug.ifBlank { "collection" }}-${game.csvLabel}.csv"
    }
}
