package com.scantrader.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.scantrader.app.data.model.Game

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "card_entries",
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("collectionId"),
        Index(value = ["collectionId", "copyKey"], unique = true, name = CardStockMigration.INDEX),
    ],
)
data class CardEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    val game: Game,
    val name: String,
    val setCode: String,
    val setName: String,
    val collectorNumber: String,
    val scryfallId: String?,
    val tcgplayerId: String?,
    val imageUrl: String?,
    val quantity: Int,
    val language: String,
    val condition: String,
    val foil: Boolean,
    /** Cardmarket EUR (non-foil). Exported as CardTrader `price_cents`. */
    val priceEur: String? = null,
    val priceEurFoil: String? = null,
    val priceUsd: String? = null,
    val priceUsdFoil: String? = null,
    /** `EUR` or `USD`. */
    val priceCurrency: String? = null,
    /** `CardTrader`, `Scryfall`, or `Cardmarket`. */
    val priceSource: String? = null,
    /** English blueprint name written to the CardTrader `name` column. */
    val catalogName: String? = null,
    /** English printing's Scryfall id when [scryfallId] is a localized print. */
    val catalogScryfallId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Finish without the foil bit (`etched`, `frame:showcase`, `promo:…`). Null for a normal print. */
    val variant: String? = null,
    /** TCGdex card id. Pokémon stock key when no Scryfall id is stored. */
    val tcgdexId: String? = null,
    /**
     * Merge key for this row inside [collectionId]. See [StockKey].
     * Empty only before [CardStockMigration] fills it.
     */
    val copyKey: String = "",
)

data class CollectionWithCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val cardCount: Int,
)
