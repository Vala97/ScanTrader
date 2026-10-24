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
        val existing = db.cards().findDuplicate(
            collectionId = collectionId,
            game = card.game,
            name = card.name,
            setCode = card.setCode,
            collectorNumber = card.collectorNumber,
            language = language.csvValue,
            condition = condition.csvValue,
            foil = foil,
            scryfallId = card.scryfallId,
        )
        if (existing != null) {
            db.cards().update(
                existing.copy(
                    quantity = existing.quantity + quantity,
                    priceEur = card.priceEur ?: existing.priceEur,
                    priceEurFoil = card.priceEurFoil ?: existing.priceEurFoil,
                    priceUsd = card.priceUsd ?: existing.priceUsd,
                    priceUsdFoil = card.priceUsdFoil ?: existing.priceUsdFoil,
                    priceCurrency = card.priceCurrency ?: existing.priceCurrency,
                    priceSource = card.priceSource ?: existing.priceSource,
                    imageUrl = card.imageUrl ?: existing.imageUrl,
                ),
            )
        } else {
            db.cards().insert(
                CardEntryEntity(
                    collectionId = collectionId,
                    game = card.game,
                    name = card.name,
                    setCode = card.setCode,
                    setName = card.setName,
                    collectorNumber = card.collectorNumber,
                    scryfallId = card.scryfallId,
                    tcgplayerId = card.tcgplayerId,
                    imageUrl = card.imageUrl,
                    quantity = quantity,
                    language = language.csvValue,
                    condition = condition.csvValue,
                    foil = foil,
                    priceEur = card.priceEur,
                    priceEurFoil = card.priceEurFoil,
                    priceUsd = card.priceUsd,
                    priceUsdFoil = card.priceUsdFoil,
                    priceCurrency = card.priceCurrency,
                    priceSource = card.priceSource,
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
}
