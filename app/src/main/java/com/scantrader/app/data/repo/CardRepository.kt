package com.scantrader.app.data.repo

import com.scantrader.app.data.db.AppDatabase
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CollectionEntity
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.IdentifiedCard

class CardRepository(private val db: AppDatabase) {
    fun collections() = db.collections().observeCollections()
    fun collectionList() = db.collections().observeAll()
    fun collection(id: Long) = db.collections().observeCollection(id)
    fun cards(collectionId: Long) = db.cards().observeByCollection(collectionId)

    suspend fun ensureDefaultCollection() {
        if (db.collections().count() == 0) {
            db.collections().insert(CollectionEntity(name = "Inventario"))
        }
    }

    suspend fun createCollection(name: String): Long =
        db.collections().insert(CollectionEntity(name = name.trim().ifBlank { "Nuova collezione" }))

    suspend fun deleteCollection(id: Long) {
        db.collections().deleteById(id)
    }

    suspend fun saveCard(
        collectionId: Long,
        card: IdentifiedCard,
        quantity: Int,
        language: CardLanguage,
        condition: CardCondition,
        foil: Boolean,
    ) {
        val stored = card.withoutPokemonFoil()
        val storedFoil = foil && card.game != com.scantrader.app.data.model.Game.POKEMON
        val existing = db.cards().findDuplicate(
            collectionId = collectionId,
            game = stored.game,
            name = stored.name,
            setCode = stored.setCode,
            collectorNumber = stored.collectorNumber,
            language = language.csvValue,
            condition = condition.csvValue,
            foil = storedFoil,
            scryfallId = stored.scryfallId,
        )
        if (existing != null) {
            db.cards().update(
                existing.copy(
                    quantity = existing.quantity + quantity,
                    foil = storedFoil,
                    priceEur = stored.priceEur ?: existing.priceEur,
                    priceEurFoil = if (stored.game == com.scantrader.app.data.model.Game.POKEMON) {
                        null
                    } else {
                        stored.priceEurFoil ?: existing.priceEurFoil
                    },
                    priceUsd = stored.priceUsd ?: existing.priceUsd,
                    priceUsdFoil = if (stored.game == com.scantrader.app.data.model.Game.POKEMON) {
                        null
                    } else {
                        stored.priceUsdFoil ?: existing.priceUsdFoil
                    },
                    priceCurrency = stored.priceCurrency ?: existing.priceCurrency,
                    priceSource = stored.priceSource ?: existing.priceSource,
                    imageUrl = stored.imageUrl ?: existing.imageUrl,
                    catalogName = existing.catalogName ?: stored.catalogName,
                    catalogScryfallId = existing.catalogScryfallId ?: stored.catalogScryfallId,
                ),
            )
        } else {
            db.cards().insert(
                CardEntryEntity(
                    collectionId = collectionId,
                    game = stored.game,
                    name = stored.name,
                    setCode = stored.setCode,
                    setName = stored.setName,
                    collectorNumber = stored.collectorNumber,
                    scryfallId = stored.scryfallId,
                    catalogName = stored.catalogName,
                    catalogScryfallId = stored.catalogScryfallId,
                    tcgplayerId = stored.tcgplayerId,
                    imageUrl = stored.imageUrl,
                    quantity = quantity,
                    language = language.csvValue,
                    condition = condition.csvValue,
                    foil = storedFoil,
                    priceEur = stored.priceEur,
                    priceEurFoil = stored.priceEurFoil,
                    priceUsd = stored.priceUsd,
                    priceUsdFoil = stored.priceUsdFoil,
                    priceCurrency = stored.priceCurrency,
                    priceSource = stored.priceSource,
                ),
            )
        }
    }

    suspend fun updateQuantity(entry: CardEntryEntity, quantity: Int) {
        if (quantity <= 0) db.cards().delete(entry) else db.cards().update(entry.copy(quantity = quantity))
    }

    suspend fun updateCondition(entry: CardEntryEntity, condition: CardCondition) {
        db.cards().update(entry.copy(condition = condition.csvValue))
    }

    suspend fun deleteCard(entry: CardEntryEntity) {
        db.cards().delete(entry)
    }

    suspend fun updateCard(entry: CardEntryEntity) {
        db.cards().update(entry)
    }
}
