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
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collections(): CollectionDao
    abstract fun cards(): CardEntryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "scantrader.db")
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE card_entries ADD COLUMN priceUsd TEXT")
                db.execSQL("ALTER TABLE card_entries ADD COLUMN priceUsdFoil TEXT")
                db.execSQL("ALTER TABLE card_entries ADD COLUMN priceCurrency TEXT")
                db.execSQL("ALTER TABLE card_entries ADD COLUMN priceSource TEXT")
            }
        }
    }
}
