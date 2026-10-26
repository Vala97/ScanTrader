package com.scantrader.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CollectionEntity::class, CardEntryEntity::class],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collections(): CollectionDao
    abstract fun cards(): CardEntryDao

    companion object {
        /**
         * Start versions Room may recreate when no migration path exists.
         * 4 is absent: a missing or failed 4→5 upgrade must not wipe the file.
         */
        val DESTRUCTIVE_FALLBACK_FROM: IntArray = intArrayOf(1, 2, 3)

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "scantrader.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // Versions 1–3 may still be recreated when a path is missing.
                // Version 4 must use MIGRATION_4_5. A failure there aborts and keeps the file.
                .fallbackToDestructiveMigrationFrom(*DESTRUCTIVE_FALLBACK_FROM)
                .build()

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE card_entries ADD COLUMN priceUsd TEXT")
                db.execSQL("ALTER TABLE card_entries ADD COLUMN priceUsdFoil TEXT")
                db.execSQL("ALTER TABLE card_entries ADD COLUMN priceCurrency TEXT")
                db.execSQL("ALTER TABLE card_entries ADD COLUMN priceSource TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE card_entries ADD COLUMN catalogName TEXT")
                db.execSQL("ALTER TABLE card_entries ADD COLUMN catalogScryfallId TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                CardStockMigration.migrate(db)
            }
        }
    }
}
