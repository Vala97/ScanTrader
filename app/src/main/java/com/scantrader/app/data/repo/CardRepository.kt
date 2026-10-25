package com.scantrader.app.data.repo

import androidx.room.withTransaction
import com.scantrader.app.data.db.AppDatabase
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CollectionEntity
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
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
        val storedFoil = foil && card.game != Game.POKEMON
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
                    priceEurFoil = if (stored.game == Game.POKEMON) {
                        null
                    } else {
                        stored.priceEurFoil ?: existing.priceEurFoil
                    },
                    priceUsd = stored.priceUsd ?: existing.priceUsd,
                    priceUsdFoil = if (stored.game == Game.POKEMON) {
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
        updateTraits(entry, quantity, entry.language, entry.condition, entry.foil)
    }

    suspend fun updateCondition(entry: CardEntryEntity, condition: CardCondition) {
        updateTraits(entry, entry.quantity, entry.language, condition.csvValue, entry.foil)
    }

    suspend fun updateLanguage(entry: CardEntryEntity, language: CardLanguage) {
        updateTraits(entry, entry.quantity, language.csvValue, entry.condition, entry.foil)
    }

    suspend fun updateFoil(entry: CardEntryEntity, foil: Boolean) {
        updateTraits(entry, entry.quantity, entry.language, entry.condition, foil)
    }

    /**
     * Quantity, language, condition, and foil share the duplicate key.
     * A colliding row absorbs the quantity and the edited row is removed.
     * Pokémon is stored non-foil. Quantity at or below zero deletes the row.
     */
    private suspend fun updateTraits(
        entry: CardEntryEntity,
        quantity: Int,
        language: String,
        condition: String,
        foil: Boolean,
    ) {
        if (quantity <= 0) {
            db.cards().delete(entry)
            return
        }
        val storedFoil = foil && entry.game != Game.POKEMON
        val duplicate = db.cards().findDuplicate(
            collectionId = entry.collectionId,
            game = entry.game,
            name = entry.name,
            setCode = entry.setCode,
            collectorNumber = entry.collectorNumber,
            language = language,
            condition = condition,
            foil = storedFoil,
            scryfallId = entry.scryfallId,
        )
        if (duplicate != null && duplicate.id != entry.id) {
            db.withTransaction {
                db.cards().update(duplicate.copy(quantity = duplicate.quantity + quantity))
                db.cards().delete(entry)
            }
        } else {
            db.cards().update(
                entry.copy(
                    quantity = quantity,
                    language = language,
                    condition = condition,
                    foil = storedFoil,
                ),
            )
        }
    }

    suspend fun deleteCard(entry: CardEntryEntity) {
        db.cards().delete(entry)
    }

    suspend fun updateCard(entry: CardEntryEntity) {
        db.cards().update(entry)
    }
}
