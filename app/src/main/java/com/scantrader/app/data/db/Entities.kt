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
    indices = [Index("collectionId")],
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
    /** Cardmarket EUR (non-foil) from Scryfall, for CardTrader `price` export. */
    val priceEur: String? = null,
    val priceEurFoil: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class CollectionWithCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val cardCount: Int,
)
