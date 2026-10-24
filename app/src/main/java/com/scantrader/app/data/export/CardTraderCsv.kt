package com.scantrader.app.data.export

import com.scantrader.app.data.catalog.ExpansionCodes
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.model.Game
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * CardTrader CSV for `POST /product_imports` (one file per game).
 *
 * Language codes are the `mtg_language` possible values on the Magic Single
 * Card category in the Full API reference: `en`, `fr`, `de`, `it`, `jp`, `kr`,
 * `pt`, `es`, `zh-CN` (also `zh-TW`, `ru`, and others we do not store).
 * Japanese is `jp`, Korean is `kr`, Simplified Chinese is `zh-CN`.
 * The CSV column is `language` for every game. Pokémon orders use the same
 * style (`pokemon_language` = `en`); the reference does not publish a separate
 * Pokémon list, so both games share these codes.
 *
 * Condition values are the documented possible values. The app’s Damaged maps
 * to `Poor`. The prose also mentions Mint, which is not in `possible_values`,
 * so it is not written. `Played` is accepted if already stored.
 *
 * `price_cents` is an integer number of cents in the seller currency (EUR).
 */
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
        "price_cents",
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
        "price_cents",
    )

    fun build(entries: List<CardEntryEntity>, game: Game): String {
        val header = if (game == Game.MTG) mtgHeader() else pokemonHeader()
        val rows = entries.filter { it.game == game }.map { row(it, game) }
        return (listOf(header) + rows).joinToString("\n") { csvLine(it) } + "\n"
    }

    fun shareTitle(games: List<Game>): String = when (games.size) {
        0 -> "Esporta CSV CardTrader"
        1 -> "Esporta CSV CardTrader · ${games.first().shareLabel}"
        else -> "Esporta CSV CardTrader · un file per gioco"
    }

    private fun row(entry: CardEntryEntity, game: Game): List<String> {
        val foil = if (entry.foil) "true" else "false"
        val price = exportPriceCents(entry).orEmpty()
        val language = exportLanguage(entry.language)
        val condition = exportCondition(entry.condition)
        return if (game == Game.MTG) {
            listOf(
                entry.scryfallId.orEmpty(),
                entry.name,
                ExpansionCodes.mtg(entry.setCode),
                entry.collectorNumber,
                entry.quantity.toString(),
                language,
                condition,
                foil,
                price,
            )
        } else {
            listOf(
                entry.name,
                ExpansionCodes.pokemon(entry.setCode),
                entry.collectorNumber,
                entry.quantity.toString(),
                language,
                condition,
                foil,
                entry.tcgplayerId.orEmpty(),
                price,
            )
        }
    }

    /**
     * Foil rows use the foil EUR price when it is present.
     * A missing or non-positive price leaves the cell empty.
     */
    fun exportPriceCents(entry: CardEntryEntity): String? {
        val raw = if (entry.foil) entry.priceEurFoil else entry.priceEur
        return priceToCents(raw)
    }

    fun priceToCents(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val parsed = raw.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
        if (parsed <= BigDecimal.ZERO) return null
        val cents = parsed.movePointRight(2).setScale(0, RoundingMode.HALF_UP)
        if (cents <= BigDecimal.ZERO) return null
        return cents.toPlainString()
    }

    fun exportLanguage(raw: String): String =
        LANGUAGE_CODES[raw.trim().lowercase(Locale.ROOT)] ?: raw.trim()

    fun exportCondition(raw: String): String =
        when (raw.trim().lowercase(Locale.ROOT)) {
            "near mint" -> "Near Mint"
            "slightly played" -> "Slightly Played"
            "moderately played" -> "Moderately Played"
            "heavily played" -> "Heavily Played"
            "played" -> "Played"
            "damaged", "poor" -> "Poor"
            else -> raw.trim()
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

    private val LANGUAGE_CODES = mapOf(
        "english" to "en",
        "en" to "en",
        "italian" to "it",
        "italiano" to "it",
        "it" to "it",
        "french" to "fr",
        "français" to "fr",
        "francais" to "fr",
        "fr" to "fr",
        "german" to "de",
        "deutsch" to "de",
        "de" to "de",
        "spanish" to "es",
        "español" to "es",
        "espanol" to "es",
        "es" to "es",
        "portuguese" to "pt",
        "português" to "pt",
        "portugues" to "pt",
        "pt" to "pt",
        "japanese" to "jp",
        "日本語" to "jp",
        "ja" to "jp",
        "jp" to "jp",
        "korean" to "kr",
        "한국어" to "kr",
        "ko" to "kr",
        "kr" to "kr",
        "chinese" to "zh-CN",
        "简体中文" to "zh-CN",
        "zh" to "zh-CN",
        "zh-cn" to "zh-CN",
        "zh_cn" to "zh-CN",
        "zh-tw" to "zh-TW",
        "zh_tw" to "zh-TW",
    )
}
