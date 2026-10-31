package com.scantrader.app.data.export

import com.scantrader.app.data.catalog.PokemonCatalog
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.model.Game
import com.scantrader.app.network.scryfall.ScryfallClient
import com.scantrader.app.network.tcgdex.TcgdexApi

/**
 * Fills [CardEntryEntity.catalogName] for rows saved before the blueprint name
 * was stored. Magic uses the Scryfall `name` (always English) and, when that
 * printing is not English, the id of the same set and collector in `en`.
 * Pokémon uses the TCGdex English card that shares the id. A failed lookup
 * leaves the row unchanged so the export still uses the stored title.
 */
class BlueprintNames(
    private val scryfall: ScryfallClient,
    private val tcgdex: TcgdexApi,
    private val save: suspend (CardEntryEntity) -> Unit,
) {
    suspend fun fill(entries: List<CardEntryEntity>): List<CardEntryEntity> =
        entries.map { entry ->
            runCatching { fillOne(entry) }.getOrDefault(entry)
        }

    private suspend fun fillOne(entry: CardEntryEntity): CardEntryEntity {
        if (!entry.catalogName.isNullOrBlank()) return entry
        val updated = when (entry.game) {
            Game.MTG -> fillMtg(entry)
            Game.POKEMON -> fillPokemon(entry)
            Game.RIFTBOUND, Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA, Game.OTHER -> null // il nome CardTrader e' gia' quello del blueprint
        } ?: return entry
        if (updated.catalogName.isNullOrBlank()) return entry
        save(updated)
        return updated
    }

    private suspend fun fillMtg(entry: CardEntryEntity): CardEntryEntity? {
        val id = entry.scryfallId?.takeIf { it.isNotBlank() } ?: return null
        val card = runCatching { scryfall.cardById(id) }.getOrNull() ?: return null
        val lang = card.lang?.trim()?.lowercase()
        if (lang.isNullOrEmpty() || lang == "en") {
            return entry.copy(catalogName = card.name.takeIf { it.isNotBlank() } ?: return null)
        }
        val english = runCatching {
            scryfall.cardBySetNumberLang(card.set, card.collectorNumber, "en")
        }.getOrNull()
        val catalogName = english?.name?.takeIf { it.isNotBlank() } ?: card.name.takeIf { it.isNotBlank() }
            ?: return null
        val catalogId = english?.id?.takeIf { it.isNotBlank() && it != card.id }
        return entry.copy(catalogName = catalogName, catalogScryfallId = catalogId)
    }

    private suspend fun fillPokemon(entry: CardEntryEntity): CardEntryEntity? {
        for (id in PokemonCatalog.cardIds(entry.setCode, entry.collectorNumber)) {
            val card = runCatching { tcgdex.card("en", id) }.getOrNull() ?: continue
            val name = card.name.takeIf { it.isNotBlank() } ?: continue
            return entry.copy(catalogName = name)
        }
        return null
    }
}
