package com.scantrader.app.data.db

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.scantrader.app.data.model.Game

/**
 * Version 4 to 5. Adds variant, Pokémon id, and the stock key, merges duplicate
 * rows, then creates the unique index. Throws on failure so Room does not
 * continue; version 4 is not in the destructive-fallback list.
 */
object CardStockMigration {
    const val INDEX = "index_card_entries_collectionId_copyKey"

    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE card_entries ADD COLUMN variant TEXT")
        db.execSQL("ALTER TABLE card_entries ADD COLUMN tcgdexId TEXT")
        db.execSQL("ALTER TABLE card_entries ADD COLUMN copyKey TEXT NOT NULL DEFAULT ''")
        val rows = readAll(db)
        val merged = StockMerge.collapse(rows)
        val keep = merged.map { it.id }.toSet()
        for (row in rows) {
            if (row.id !in keep) {
                db.execSQL("DELETE FROM card_entries WHERE id = ?", arrayOf<Any>(row.id))
            }
        }
        val update = db.compileStatement(
            """
            UPDATE card_entries SET
                name = ?, setCode = ?, setName = ?, collectorNumber = ?,
                scryfallId = ?, tcgplayerId = ?, imageUrl = ?, quantity = ?,
                language = ?, `condition` = ?, foil = ?,
                priceEur = ?, priceEurFoil = ?, priceUsd = ?, priceUsdFoil = ?,
                priceCurrency = ?, priceSource = ?, catalogName = ?, catalogScryfallId = ?,
                createdAt = ?, variant = ?, tcgdexId = ?, copyKey = ?
            WHERE id = ?
            """.trimIndent(),
        )
        try {
            for (row in merged) updateRow(update, row)
        } finally {
            update.close()
        }
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `$INDEX` ON `card_entries` (`collectionId`, `copyKey`)",
        )
    }

    private fun updateRow(statement: SupportSQLiteStatement, row: CardEntryEntity) {
        statement.clearBindings()
        var index = 1
        fun text(value: String?) {
            if (value == null) statement.bindNull(index) else statement.bindString(index, value)
            index++
        }
        text(row.name)
        text(row.setCode)
        text(row.setName)
        text(row.collectorNumber)
        text(row.scryfallId)
        text(row.tcgplayerId)
        text(row.imageUrl)
        statement.bindLong(index++, row.quantity.toLong())
        text(row.language)
        text(row.condition)
        statement.bindLong(index++, if (row.foil) 1L else 0L)
        text(row.priceEur)
        text(row.priceEurFoil)
        text(row.priceUsd)
        text(row.priceUsdFoil)
        text(row.priceCurrency)
        text(row.priceSource)
        text(row.catalogName)
        text(row.catalogScryfallId)
        statement.bindLong(index++, row.createdAt)
        text(row.variant)
        text(row.tcgdexId)
        text(row.copyKey)
        statement.bindLong(index, row.id)
        statement.executeUpdateDelete()
    }

    private fun readAll(db: SupportSQLiteDatabase): List<CardEntryEntity> {
        val cursor = db.query("SELECT * FROM card_entries")
        cursor.use {
            val rows = ArrayList<CardEntryEntity>(it.count.coerceAtLeast(0))
            while (it.moveToNext()) rows += it.toEntry()
            return rows
        }
    }

    private fun Cursor.toEntry(): CardEntryEntity = CardEntryEntity(
        id = long("id"),
        collectionId = long("collectionId"),
        game = Game.valueOf(string("game") ?: Game.MTG.name),
        name = string("name").orEmpty(),
        setCode = string("setCode").orEmpty(),
        setName = string("setName").orEmpty(),
        collectorNumber = string("collectorNumber").orEmpty(),
        scryfallId = string("scryfallId"),
        tcgplayerId = string("tcgplayerId"),
        imageUrl = string("imageUrl"),
        quantity = int("quantity"),
        language = string("language").orEmpty(),
        condition = string("condition").orEmpty(),
        foil = int("foil") != 0,
        priceEur = string("priceEur"),
        priceEurFoil = string("priceEurFoil"),
        priceUsd = string("priceUsd"),
        priceUsdFoil = string("priceUsdFoil"),
        priceCurrency = string("priceCurrency"),
        priceSource = string("priceSource"),
        catalogName = string("catalogName"),
        catalogScryfallId = string("catalogScryfallId"),
        createdAt = long("createdAt"),
        variant = optionalString("variant"),
        tcgdexId = optionalString("tcgdexId"),
        copyKey = optionalString("copyKey").orEmpty(),
    )

    private fun Cursor.string(column: String): String? = optionalString(column)

    private fun Cursor.optionalString(column: String): String? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
}
