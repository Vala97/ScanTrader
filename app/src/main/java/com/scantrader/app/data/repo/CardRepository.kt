package com.scantrader.app.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.scantrader.app.data.db.AppDatabase
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CardPrice
import com.scantrader.app.data.db.CollectionEntity
import com.scantrader.app.data.db.StockKey
import com.scantrader.app.data.db.StockMerge
import com.scantrader.app.data.db.stamped
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.scan.CardFinish

enum class RenameCollection { Saved, Empty, Duplicate }

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

    /**
     * Changes only the collection name. Cards stay on the same id.
     * A blank name is refused. Another collection with the same name, ignoring
     * case and surrounding spaces, is refused.
     */
    suspend fun renameCollection(id: Long, name: String): RenameCollection {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return RenameCollection.Empty
        val owner = db.collections().idWithName(trimmed)
        if (owner != null && owner != id) return RenameCollection.Duplicate
        db.collections().rename(id, trimmed)
        return RenameCollection.Saved
    }

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
        val incoming = CardEntryEntity(
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
            tcgdexId = stored.tcgdexId,
            imageUrl = stored.imageUrl,
            quantity = quantity,
            language = language.csvValue,
            condition = condition.csvValue,
            foil = foil && stored.game != Game.POKEMON,
            priceEur = stored.priceEur,
            priceEurFoil = stored.priceEurFoil,
            priceUsd = stored.priceUsd,
            priceUsdFoil = stored.priceUsdFoil,
            priceCurrency = stored.priceCurrency,
            priceSource = stored.priceSource,
            variant = CardFinish.variantOf(stored.finish),
        ).stamped()
        val existing = db.cards().findByCopyKey(collectionId, incoming.copyKey)
            ?: legacySetRow(incoming)
        if (existing != null) {
            absorb(existing, incoming)
        } else {
            try {
                db.cards().insert(incoming)
            } catch (e: SQLiteConstraintException) {
                val raced = db.cards().findByCopyKey(collectionId, incoming.copyKey) ?: throw e
                absorb(raced, incoming)
            }
        }
    }

    /**
     * A row saved before printing ids were stored still uses set + collector number.
     * The next save of that print, which now has an id, absorbs it.
     * Trait edits do not use this bridge: changing foil must not land on another row.
     */
    private suspend fun legacySetRow(incoming: CardEntryEntity): CardEntryEntity? {
        val hasId = !incoming.catalogScryfallId.isNullOrBlank() ||
            !incoming.scryfallId.isNullOrBlank() ||
            !incoming.tcgdexId.isNullOrBlank()
        if (!hasId) return null
        val setKey = StockKey.withoutPrintingId(incoming)
        if (setKey == incoming.copyKey) return null
        return db.cards().findByCopyKey(incoming.collectionId, setKey)
    }

    private suspend fun absorb(existing: CardEntryEntity, incoming: CardEntryEntity) {
        val newer = incoming.copy(
            id = 0L,
            createdAt = maxOf(incoming.createdAt, existing.createdAt + 1L),
        )
        db.cards().update(StockMerge.merge(listOf(existing, newer)))
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
     * Stores a typed euro price, or clears it when [raw] is null so the automatic
     * amount is used again. Pokémon rows keep foil prices empty.
     * Returns false when [raw] is not a positive amount.
     */
    suspend fun setManualPrice(entry: CardEntryEntity, raw: String?): Boolean {
        val manual = if (raw == null) {
            null
        } else {
            CardPrice.parseManual(raw) ?: return false
        }
        val pokemon = entry.game == Game.POKEMON
        db.cards().update(
            entry.copy(
                manualPriceEur = manual,
                foil = entry.foil && !pokemon,
                priceEurFoil = if (pokemon) null else entry.priceEurFoil,
                priceUsdFoil = if (pokemon) null else entry.priceUsdFoil,
            ).stamped(),
        )
        return true
    }

    /**
     * Quantity, language, condition, and foil share the stock key.
     * A colliding row absorbs this one. Pokémon is stored non-foil.
     * Quantity at or below zero deletes the row.
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
        val pokemon = entry.game == Game.POKEMON
        val edited = entry.copy(
            quantity = quantity,
            language = language,
            condition = condition,
            foil = foil && !pokemon,
            priceEurFoil = if (pokemon) null else entry.priceEurFoil,
            priceUsdFoil = if (pokemon) null else entry.priceUsdFoil,
        ).stamped()
        val duplicate = db.cards().findByCopyKey(entry.collectionId, edited.copyKey)
        if (duplicate != null && duplicate.id != entry.id) {
            val merged = StockMerge.merge(
                listOf(
                    duplicate,
                    edited.copy(id = 0L, createdAt = maxOf(edited.createdAt, duplicate.createdAt + 1L)),
                ),
            )
            db.withTransaction {
                db.cards().delete(entry)
                db.cards().update(merged)
            }
        } else {
            db.cards().update(edited)
        }
    }

    suspend fun deleteCard(entry: CardEntryEntity) {
        db.cards().delete(entry)
    }

    /**
     * Blueprint fill can add [CardEntryEntity.catalogScryfallId] and move the row
     * onto another stock line. The other line absorbs it so the unique key holds.
     */
    suspend fun updateCard(entry: CardEntryEntity) {
        val pokemon = entry.game == Game.POKEMON
        val stamped = entry.copy(
            foil = entry.foil && !pokemon,
            priceEurFoil = if (pokemon) null else entry.priceEurFoil,
            priceUsdFoil = if (pokemon) null else entry.priceUsdFoil,
        ).stamped()
        val duplicate = db.cards().findByCopyKey(stamped.collectionId, stamped.copyKey)
        if (duplicate != null && duplicate.id != stamped.id && stamped.id != 0L) {
            val merged = StockMerge.merge(listOf(duplicate, stamped))
            db.withTransaction {
                if (merged.id != stamped.id) db.cards().delete(stamped)
                if (merged.id != duplicate.id) db.cards().delete(duplicate)
                db.cards().update(merged)
            }
        } else {
            db.cards().update(stamped)
        }
    }
}
