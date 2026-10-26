package com.scantrader.app.data.db

import com.scantrader.app.data.model.Game

/**
 * Collapses rows that share [StockKey]. Quantity is summed.
 * Name, image, and each price field keep the newest non-blank value.
 */
object StockMerge {
    fun collapse(rows: List<CardEntryEntity>): List<CardEntryEntity> {
        if (rows.isEmpty()) return emptyList()
        return rows
            .groupBy { it.collectionId to StockKey.of(it) }
            .values
            .map { merge(it) }
            .sortedBy { it.id }
    }

    fun merge(rows: List<CardEntryEntity>): CardEntryEntity {
        require(rows.isNotEmpty())
        val newestFirst = rows.sortedWith(
            compareByDescending<CardEntryEntity> { it.createdAt }.thenByDescending { it.id },
        )
        val survivor = rows.filter { it.id != 0L }
            .maxWithOrNull(compareBy({ it.createdAt }, { it.id }))
            ?: newestFirst.first()
        val quantity = rows.sumOf { it.quantity.coerceAtLeast(0) }
        val game = survivor.game
        val pokemon = game == Game.POKEMON
        val merged = survivor.copy(
            quantity = quantity,
            foil = if (pokemon) false else survivor.foil,
            name = pick(newestFirst) { it.name } ?: survivor.name,
            setCode = pick(newestFirst) { it.setCode } ?: survivor.setCode,
            setName = pick(newestFirst) { it.setName } ?: survivor.setName,
            collectorNumber = pick(newestFirst) { it.collectorNumber } ?: survivor.collectorNumber,
            scryfallId = pick(newestFirst) { it.scryfallId },
            tcgplayerId = pick(newestFirst) { it.tcgplayerId },
            tcgdexId = pick(newestFirst) { it.tcgdexId },
            imageUrl = pick(newestFirst) { it.imageUrl },
            priceEur = pick(newestFirst) { it.priceEur },
            priceEurFoil = if (game == Game.POKEMON) null else pick(newestFirst) { it.priceEurFoil },
            priceUsd = pick(newestFirst) { it.priceUsd },
            priceUsdFoil = if (game == Game.POKEMON) null else pick(newestFirst) { it.priceUsdFoil },
            priceCurrency = pick(newestFirst) { it.priceCurrency },
            priceSource = pick(newestFirst) { it.priceSource },
            catalogName = pick(newestFirst) { it.catalogName },
            catalogScryfallId = pick(newestFirst) { it.catalogScryfallId },
            variant = pick(newestFirst) { it.variant },
            createdAt = rows.maxOf { it.createdAt },
        )
        return merged.stamped()
    }

    private fun pick(newestFirst: List<CardEntryEntity>, select: (CardEntryEntity) -> String?): String? =
        newestFirst.firstNotNullOfOrNull { select(it)?.trim()?.takeIf { value -> value.isNotEmpty() } }
}
