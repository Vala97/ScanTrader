package com.scantrader.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query(
        """
        SELECT c.id, c.name, c.createdAt,
               CAST(COALESCE(SUM(e.quantity), 0) AS INTEGER) AS cardCount
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

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("SELECT id FROM collections WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun idWithName(name: String): Long?
}

@Dao
interface CardEntryDao {
    @Query("SELECT * FROM card_entries WHERE collectionId = :collectionId ORDER BY createdAt DESC")
    fun observeByCollection(collectionId: Long): Flow<List<CardEntryEntity>>

    @Query(
        """
        SELECT * FROM card_entries
        WHERE collectionId = :collectionId AND copyKey = :copyKey
        LIMIT 1
        """,
    )
    suspend fun findByCopyKey(collectionId: Long, copyKey: String): CardEntryEntity?

    /** Una riga dal suo id (lo scanner scala le carte vendute dalla riga da cui erano partite). */
    @Query("SELECT * FROM card_entries WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): CardEntryEntity?

    @Insert
    suspend fun insert(entity: CardEntryEntity): Long

    @Update
    suspend fun update(entity: CardEntryEntity)

    @Delete
    suspend fun delete(entity: CardEntryEntity)
}
