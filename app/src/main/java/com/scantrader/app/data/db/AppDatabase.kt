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
         * Version 1 has no migration, so an ancient file may be recreated.
         * Versions 2–5 are start or end versions of the migrations below. Room
         * rejects a builder that also lists them here.
         * Version 4 is absent: a missing 4→5 path cannot wipe the file. A
         * migration that throws rolls back and leaves the previous file in place.
         */
        val DESTRUCTIVE_FALLBACK_FROM: IntArray = intArrayOf(1)

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "scantrader.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
