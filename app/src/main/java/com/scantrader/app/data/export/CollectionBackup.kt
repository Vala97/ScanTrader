package com.scantrader.app.data.export

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CollectionEntity
import com.scantrader.app.data.db.stamped
import com.scantrader.app.data.model.Game

enum class CollectionImportMode { Add, Replace }

data class CollectionBackup(
    val version: Int = VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val collections: List<BackupCollection> = emptyList(),
) {
    companion object {
        const val VERSION = 1
        const val FILE_NAME = "scantrader-backup-v1.json"

        private val gson: Gson = GsonBuilder().serializeNulls().create()

        fun toJson(backup: CollectionBackup): String = gson.toJson(backup)

        fun parse(json: String): CollectionBackup {
            val backup = try {
                gson.fromJson(json, CollectionBackup::class.java)
            } catch (e: JsonSyntaxException) {
                throw IllegalArgumentException("Backup JSON non valido.", e)
            } ?: throw IllegalArgumentException("Backup JSON vuoto.")
            if (backup.version != VERSION) {
                throw IllegalArgumentException("Versione backup non supportata (${backup.version}).")
            }
            return backup
        }

        fun fromEntities(
            collections: List<CollectionEntity>,
            cardsByCollection: Map<Long, List<CardEntryEntity>>,
            now: Long = System.currentTimeMillis(),
        ): CollectionBackup = CollectionBackup(
            version = VERSION,
            exportedAt = now,
            collections = collections.map { collection ->
                BackupCollection(
                    name = collection.name,
                    createdAt = collection.createdAt,
                    cards = cardsByCollection[collection.id].orEmpty().map { BackupCard.from(it) },
                )
            },
        )

        /** Unique name when adding beside existing collections (case-insensitive). */
        fun uniqueImportName(desired: String, taken: Set<String>): String {
            val base = desired.trim().ifBlank { "Collezione" }
            val lowerTaken = taken.map { it.trim().lowercase() }.toMutableSet()
            if (base.lowercase() !in lowerTaken) return base
            var candidate = "$base (import)"
            var n = 2
            while (candidate.lowercase() in lowerTaken) {
                candidate = "$base (import $n)"
                n++
            }
            return candidate
        }
    }
}

data class BackupCollection(
    val name: String = "",
    val createdAt: Long = 0L,
    val cards: List<BackupCard> = emptyList(),
)

/** Card fields without Room ids. */
data class BackupCard(
    val game: Game = Game.MTG,
    val name: String = "",
    val setCode: String = "",
    val setName: String = "",
    val collectorNumber: String = "",
    val scryfallId: String? = null,
    val tcgplayerId: String? = null,
    val imageUrl: String? = null,
    val quantity: Int = 1,
    val language: String = "",
    val condition: String = "",
    val foil: Boolean = false,
    val priceEur: String? = null,
    val priceEurFoil: String? = null,
    val priceUsd: String? = null,
    val priceUsdFoil: String? = null,
    val priceCurrency: String? = null,
    val priceSource: String? = null,
    val catalogName: String? = null,
    val catalogScryfallId: String? = null,
    val createdAt: Long = 0L,
    val variant: String? = null,
    val tcgdexId: String? = null,
    val manualPriceEur: String? = null,
) {
    fun toEntity(collectionId: Long): CardEntryEntity = CardEntryEntity(
        collectionId = collectionId,
        game = game,
        name = name,
        setCode = setCode,
        setName = setName,
        collectorNumber = collectorNumber,
        scryfallId = scryfallId,
        tcgplayerId = tcgplayerId,
        imageUrl = imageUrl,
        quantity = quantity.coerceAtLeast(1),
        language = language,
        condition = condition,
        foil = foil && game != Game.POKEMON,
        priceEur = priceEur,
        priceEurFoil = if (game == Game.POKEMON) null else priceEurFoil,
        priceUsd = priceUsd,
        priceUsdFoil = if (game == Game.POKEMON) null else priceUsdFoil,
        priceCurrency = priceCurrency,
        priceSource = priceSource,
        catalogName = catalogName,
        catalogScryfallId = catalogScryfallId,
        createdAt = createdAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
        variant = variant,
        tcgdexId = tcgdexId,
        manualPriceEur = manualPriceEur,
    ).stamped()

    companion object {
        fun from(entry: CardEntryEntity) = BackupCard(
            game = entry.game,
            name = entry.name,
            setCode = entry.setCode,
            setName = entry.setName,
            collectorNumber = entry.collectorNumber,
            scryfallId = entry.scryfallId,
            tcgplayerId = entry.tcgplayerId,
            imageUrl = entry.imageUrl,
            quantity = entry.quantity,
            language = entry.language,
            condition = entry.condition,
            foil = entry.foil,
            priceEur = entry.priceEur,
            priceEurFoil = entry.priceEurFoil,
            priceUsd = entry.priceUsd,
            priceUsdFoil = entry.priceUsdFoil,
            priceCurrency = entry.priceCurrency,
            priceSource = entry.priceSource,
            catalogName = entry.catalogName,
            catalogScryfallId = entry.catalogScryfallId,
            createdAt = entry.createdAt,
            variant = entry.variant,
            tcgdexId = entry.tcgdexId,
            manualPriceEur = entry.manualPriceEur,
        )
    }
}
