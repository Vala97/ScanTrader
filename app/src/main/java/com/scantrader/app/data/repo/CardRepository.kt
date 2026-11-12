package com.scantrader.app.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.scantrader.app.data.db.AppDatabase
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CardPrice
import com.scantrader.app.data.db.CollectionEntity
import com.scantrader.app.data.db.CollectionMove
import com.scantrader.app.data.db.StockKey
import com.scantrader.app.data.db.StockMerge
import com.scantrader.app.data.db.stamped
import com.scantrader.app.data.export.CollectionBackup
import com.scantrader.app.data.export.CollectionImportMode
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.scan.CardFinish

enum class RenameCollection { Saved, Empty, Duplicate }

enum class MoveCardsKind {
    Moved,
    NothingToMove,
    SameCollection,
    MissingDestination,
    EmptyName,
    MirrorBlocked,
}

data class MoveCardsResult(
    val kind: MoveCardsKind,
    val copies: Int = 0,
    val sent: List<CollectionMove.SentMove> = emptyList(),
)

data class CollectionImportResult(val collections: Int, val cards: Int)

sealed class SingleCopyEditResult {
    data class Saved(val hostId: Long, val ownedQuantity: Int) : SingleCopyEditResult()
    data object Removed : SingleCopyEditResult()
    data object Missing : SingleCopyEditResult()
}

data class DeleteCardsResult(val rows: Int, val copies: Int)

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

    /**
     * Moves copies into another local collection. One transaction.
     * Does not write the tracker queue. [mirrorId] cannot be a source or a destination:
     * that collection is the CardTrader mirror and is rewritten by the tracker sync.
     * A missing source row is skipped. A request larger than the row moves only what remains.
     */
    suspend fun moveCards(
        sourceId: Long,
        destinationId: Long?,
        newCollectionName: String?,
        requests: List<CollectionMove.Request>,
        mirrorId: Long?,
    ): MoveCardsResult {
        if (mirrorId != null && (sourceId == mirrorId || destinationId == mirrorId)) {
            return MoveCardsResult(MoveCardsKind.MirrorBlocked)
        }
        if (destinationId != null && destinationId == sourceId) {
            return MoveCardsResult(MoveCardsKind.SameCollection)
        }
        val createdName = newCollectionName?.trim()
        if (destinationId == null && createdName.isNullOrEmpty()) {
            return MoveCardsResult(MoveCardsKind.EmptyName)
        }
        if (destinationId != null && db.collections().getAll().none { it.id == destinationId }) {
            return MoveCardsResult(MoveCardsKind.MissingDestination)
        }
        val sent = mutableListOf<CollectionMove.SentMove>()
        var copies = 0
        db.withTransaction {
            val created = destinationId == null
            val destId = destinationId ?: db.collections().insert(CollectionEntity(name = createdName!!))
            val fresh = requests.mapNotNull { request ->
                val row = db.cards().byId(request.sourceId)
                if (row == null || row.collectionId != sourceId) null else request
            }
            val plan = CollectionMove.plan(
                requests = fresh,
                sourceRows = fresh.mapNotNull { db.cards().byId(it.sourceId) },
                destinationRows = db.cards().listByCollection(destId),
                destinationId = destId,
            )
            if (plan.copiesMoved == 0) {
                if (created) db.collections().deleteById(destId)
            } else {
                for (op in plan.ops) {
                    when (op) {
                        is CollectionMove.Op.Update -> db.cards().update(op.row)
                        is CollectionMove.Op.Delete -> db.cards().byId(op.id)?.let { db.cards().delete(it) }
                        is CollectionMove.Op.Insert -> {
                            val newId = db.cards().insert(op.row.copy(id = 0L))
                            if (op.sent.movedCopies > 0) {
                                sent += op.sent.copy(destinationId = newId)
                            }
                        }
                        is CollectionMove.Op.Retarget -> sent += op.sent
                    }
                }
                copies = plan.copiesMoved
            }
        }
        return if (copies == 0) {
            MoveCardsResult(MoveCardsKind.NothingToMove)
        } else {
            MoveCardsResult(MoveCardsKind.Moved, copies = copies, sent = sent)
        }
    }

    suspend fun saveCard(
        collectionId: Long,
        card: IdentifiedCard,
        quantity: Int,
        language: CardLanguage,
        condition: CardCondition,
        foil: Boolean,
    ): Long? {
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
        // la riga dove sono finite le copie (serve allo scanner per sapere cosa ha gia' mandato al tracker)
        return (db.cards().findByCopyKey(collectionId, incoming.copyKey) ?: existing)?.id
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

    /** The row only if it is still in this collection. A move or delete is absent. */
    suspend fun cardInCollection(collectionId: Long, id: Long): CardEntryEntity? {
        val row = db.cards().byId(id) ?: return null
        return row.takeIf { it.collectionId == collectionId && it.quantity > 0 }
    }

    /**
     * Changes language, condition, foil, or quantity for [ownedQuantity] copies
     * on [hostId]. Copies that were already on that row stay as they are.
     * A matching stock line only gains quantity: its price and other fields stay.
     */
    suspend fun editSingleCopy(
        collectionId: Long,
        hostId: Long,
        ownedQuantity: Int,
        language: CardLanguage,
        condition: CardCondition,
        foil: Boolean,
        quantity: Int,
    ): SingleCopyEditResult = db.withTransaction {
        val host = cardInCollection(collectionId, hostId) ?: return@withTransaction SingleCopyEditResult.Missing
        val owned = ownedQuantity.coerceIn(0, host.quantity)
        if (owned <= 0) return@withTransaction SingleCopyEditResult.Missing
        if (quantity <= 0) {
            reduceCopies(host, owned)
            return@withTransaction SingleCopyEditResult.Removed
        }
        val pokemon = host.game == Game.POKEMON
        val desired = host.copy(
            id = 0L,
            quantity = quantity,
            language = language.csvValue,
            condition = condition.csvValue,
            foil = foil && !pokemon,
            priceEurFoil = if (pokemon) null else host.priceEurFoil,
            priceUsdFoil = if (pokemon) null else host.priceUsdFoil,
        ).stamped()
        if (desired.copyKey == host.copyKey) {
            val kept = host.copy(quantity = host.quantity - owned + quantity).stamped()
            db.cards().update(kept)
            return@withTransaction SingleCopyEditResult.Saved(kept.id, quantity)
        }
        reduceCopies(host, owned)
        val other = db.cards().findByCopyKey(collectionId, desired.copyKey)
        if (other != null) {
            val kept = other.copy(quantity = other.quantity + quantity)
            db.cards().update(kept)
            SingleCopyEditResult.Saved(kept.id, quantity)
        } else {
            val newId = db.cards().insert(desired)
            SingleCopyEditResult.Saved(newId, quantity)
        }
    }

    private suspend fun reduceCopies(host: CardEntryEntity, owned: Int) {
        val remain = host.quantity - owned
        if (remain <= 0) db.cards().delete(host)
        else db.cards().update(host.copy(quantity = remain).stamped())
    }

    /** Deletes the selected rows of this collection. Rows elsewhere are left alone. */
    suspend fun deleteCards(collectionId: Long, ids: Collection<Long>): DeleteCardsResult {
        var rows = 0
        var copies = 0
        db.withTransaction {
            for (id in ids.distinct()) {
                val row = db.cards().byId(id) ?: continue
                if (row.collectionId != collectionId) continue
                copies += row.quantity.coerceAtLeast(0)
                db.cards().delete(row)
                rows++
            }
        }
        return DeleteCardsResult(rows, copies)
    }

    /** Full ScanTrader backup of user collections (exclude mirror / other ids). */
    suspend fun exportBackup(excludeCollectionIds: Set<Long> = emptySet()): CollectionBackup {
        val collections = db.collections().getAll().filter { it.id !in excludeCollectionIds }
        val cardsByCollection = collections.associate { collection ->
            collection.id to db.cards().listByCollection(collection.id)
        }
        return CollectionBackup.fromEntities(collections, cardsByCollection)
    }

    /**
     * Restores a backup. [Add] creates new collections beside the current ones.
     * [Replace] deletes every collection except [excludeFromReplaceIds], then inserts.
     */
    suspend fun importBackup(
        backup: CollectionBackup,
        mode: CollectionImportMode,
        excludeFromReplaceIds: Set<Long> = emptySet(),
    ): CollectionImportResult = db.withTransaction {
        if (mode == CollectionImportMode.Replace) {
            for (collection in db.collections().getAll()) {
                if (collection.id !in excludeFromReplaceIds) {
                    db.collections().deleteById(collection.id)
                }
            }
        }
        var collectionCount = 0
        var cardCount = 0
        val taken = db.collections().getAll().map { it.name }.toMutableSet()
        for (item in backup.collections) {
            // Always uniquify against leftovers (e.g. Cardtrader mirror after Replace).
            val name = CollectionBackup.uniqueImportName(
                item.name.trim().ifBlank { "Collezione" },
                taken,
            )
            taken += name
            val collectionId = db.collections().insert(
                CollectionEntity(
                    name = name,
                    createdAt = item.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                ),
            )
            collectionCount++
            for (card in item.cards) {
                db.cards().insert(card.toEntity(collectionId))
                cardCount++
            }
        }
        ensureDefaultCollection()
        CollectionImportResult(collections = collectionCount, cards = cardCount)
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
