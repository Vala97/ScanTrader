package com.scantrader.app.data.db

import com.scantrader.app.data.model.Game

/**
 * One stock line: collection is applied by the caller.
 * English printing id (`catalogScryfallId`, else `scryfallId`, else `tcgdexId`)
 * when any id is present. Otherwise set (case-insensitive) and collector number
 * with leading zeros removed and the letter suffix kept.
 * Language, condition, foil, and variant (finish without the foil bit) always count.
 * The printed name does not.
 */
object StockKey {
    fun of(entry: CardEntryEntity): String = of(
        game = entry.game,
        catalogScryfallId = entry.catalogScryfallId,
        scryfallId = entry.scryfallId,
        tcgdexId = entry.tcgdexId,
        setCode = entry.setCode,
        collectorNumber = entry.collectorNumber,
        language = entry.language,
        condition = entry.condition,
        foil = entry.foil,
        variant = entry.variant,
    )

    fun of(
        game: Game,
        catalogScryfallId: String?,
        scryfallId: String?,
        tcgdexId: String?,
        setCode: String,
        collectorNumber: String,
        language: String,
        condition: String,
        foil: Boolean,
        variant: String?,
    ): String {
        val id = listOf(catalogScryfallId, scryfallId, tcgdexId).firstOrNull { !it.isNullOrBlank() }?.trim()
        val storedFoil = foil && game != Game.POKEMON
        val tail = listOf(
            language.trim(),
            condition.trim(),
            if (storedFoil) "1" else "0",
            variant?.trim()?.lowercase().orEmpty(),
        ).joinToString(SEP)
        return if (id != null) {
            listOf("id", game.name, id, tail).joinToString(SEP)
        } else {
            listOf("set", game.name, setCode.trim().lowercase(), normalizeCollector(collectorNumber), tail)
                .joinToString(SEP)
        }
    }

    /** `034` and `34` match. `105` and `105a` do not. Suffix case is ignored. */
    fun normalizeCollector(raw: String): String {
        val trimmed = raw.trim()
        val match = Regex("""^(0*)(\d+)(.*)$""").find(trimmed) ?: return trimmed.lowercase()
        val number = match.groupValues[2].trimStart('0').ifEmpty { "0" }
        return number + match.groupValues[3].lowercase()
    }

    fun withoutPrintingId(entry: CardEntryEntity): String = of(
        game = entry.game,
        catalogScryfallId = null,
        scryfallId = null,
        tcgdexId = null,
        setCode = entry.setCode,
        collectorNumber = entry.collectorNumber,
        language = entry.language,
        condition = entry.condition,
        foil = entry.foil,
        variant = entry.variant,
    )

    private const val SEP = "\u001f"
}

fun CardEntryEntity.stamped(): CardEntryEntity {
    val key = StockKey.of(this)
    return if (copyKey == key) this else copy(copyKey = key)
}
