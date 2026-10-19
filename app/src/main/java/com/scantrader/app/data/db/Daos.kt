package com.scantrader.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.scantrader.app.data.model.Game
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query(
        """
        SELECT c.id, c.name, c.createdAt, COUNT(e.id) AS cardCount
        FROM collections c
        LEFT JOIN card_entries e ON e.collectionId = c.id
        GROUP BY c.id
        ORDER BY c.createdAt DESC
        """,
    )
    fun observeCollections(): Flow<List<CollectionWithCount>>

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observeCollection(id: Long): Flow<CollectionEntity?>

    @Query("SELECT * FROM collections ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("SELECT COUNT(*) FROM collections")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: CollectionEntity): Long

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CardEntryDao {
    @Query("SELECT * FROM card_entries WHERE collectionId = :collectionId ORDER BY createdAt DESC")
    fun observeByCollection(collectionId: Long): Flow<List<CardEntryEntity>>

    @Query(
        """
        SELECT * FROM card_entries
        WHERE collectionId = :collectionId
          AND game = :game
          AND name = :name
          AND setCode = :setCode
          AND collectorNumber = :collectorNumber
          AND language = :language
          AND `condition` = :condition
          AND foil = :foil
          AND IFNULL(scryfallId, '') = IFNULL(:scryfallId, '')
        LIMIT 1
        """,
    )
    suspend fun findDuplicate(
        collectionId: Long,
        game: Game,
        name: String,
        setCode: String,
        collectorNumber: String,
        language: String,
        condition: String,
        foil: Boolean,
        scryfallId: String?,
    ): CardEntryEntity?

    @Insert
    suspend fun insert(entity: CardEntryEntity): Long

    @Update
    suspend fun update(entity: CardEntryEntity)

    @Delete
    suspend fun delete(entity: CardEntryEntity)
}
