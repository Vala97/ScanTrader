package com.scantrader.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.scantrader.app.data.db.AppDatabase
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Opens the database through [AppDatabase.create], the same builder the process
 * uses at startup. Room rejects that builder when a migration start or end
 * version is also a destructive-fallback start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AppDatabaseBuilderTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    @Before
    @After
    fun deleteDatabaseFile() {
        val file = context.getDatabasePath("scantrader.db")
        file.parentFile?.mkdirs()
        file.delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        File(file.path + "-journal").delete()
    }

    @Test
    fun realBuilderOpens() {
        val versions = AppDatabase.DESTRUCTIVE_FALLBACK_FROM.toSet()
        assertFalse(versions.contains(2))
        assertFalse(versions.contains(3))
        assertFalse(versions.contains(4))
        assertFalse(versions.contains(5))
        assertFalse(versions.contains(6))
        val db = AppDatabase.create(context)
        try {
            assertEquals(6, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun versionFourFileIsMigratedAndNotWiped() {
        plantVersionFour(context.getDatabasePath("scantrader.db"))
        val db = AppDatabase.create(context)
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(6, sqlite.version)
            sqlite.query("SELECT COUNT(*) FROM collections").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            sqlite.query("SELECT name FROM collections").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Inventario", cursor.getString(0))
            }
            sqlite.query(
                "SELECT id, name, quantity, imageUrl, priceEur FROM card_entries ORDER BY id",
            ).use { cursor ->
                assertEquals(2, cursor.count)
                cursor.moveToNext()
                assertEquals(2, cursor.getInt(0))
                assertEquals("Perfected Theory", cursor.getString(1))
                assertEquals(3, cursor.getInt(2))
                assertEquals("it.jpg", cursor.getString(3))
                assertEquals("1.20", cursor.getString(4))
                cursor.moveToNext()
                assertEquals(3, cursor.getInt(0))
                assertEquals("Lightning Bolt", cursor.getString(1))
                assertEquals(4, cursor.getInt(2))
            }
            sqlite.query("SELECT manualPriceEur FROM card_entries WHERE id = 2").use { cursor ->
                cursor.moveToNext()
                assertTrue(cursor.isNull(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun versionFiveFileKeepsPricesAndAddsManualPrice() {
        plantVersionFive(context.getDatabasePath("scantrader.db"))
        val db = AppDatabase.create(context)
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(6, sqlite.version)
            sqlite.query("SELECT name FROM collections").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Inventario", cursor.getString(0))
            }
            sqlite.query(
                "SELECT name, priceEur, priceEurFoil, manualPriceEur FROM card_entries ORDER BY id",
            ).use { cursor ->
                assertEquals(2, cursor.count)
                cursor.moveToNext()
                assertEquals("Lightning Bolt", cursor.getString(0))
                assertEquals("1.25", cursor.getString(1))
                assertEquals("9.99", cursor.getString(2))
                assertTrue(cursor.isNull(3))
                cursor.moveToNext()
                assertEquals("Pikachu", cursor.getString(0))
                assertEquals("2.00", cursor.getString(1))
                assertEquals("8.00", cursor.getString(2))
                assertTrue(cursor.isNull(3))
            }
        } finally {
            db.close()
        }
    }

    private fun plantVersionFive(file: File) {
        file.parentFile?.mkdirs()
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        sqlite.use {
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `collections` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `card_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `collectionId` INTEGER NOT NULL,
                    `game` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `setCode` TEXT NOT NULL,
                    `setName` TEXT NOT NULL,
                    `collectorNumber` TEXT NOT NULL,
                    `scryfallId` TEXT,
                    `tcgplayerId` TEXT,
                    `imageUrl` TEXT,
                    `quantity` INTEGER NOT NULL,
                    `language` TEXT NOT NULL,
                    `condition` TEXT NOT NULL,
                    `foil` INTEGER NOT NULL,
                    `priceEur` TEXT,
                    `priceEurFoil` TEXT,
                    `priceUsd` TEXT,
                    `priceUsdFoil` TEXT,
                    `priceCurrency` TEXT,
                    `priceSource` TEXT,
                    `catalogName` TEXT,
                    `catalogScryfallId` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `variant` TEXT,
                    `tcgdexId` TEXT,
                    `copyKey` TEXT NOT NULL,
                    FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            it.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_card_entries_collectionId` ON `card_entries` (`collectionId`)",
            )
            it.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_card_entries_collectionId_copyKey` " +
                    "ON `card_entries` (`collectionId`, `copyKey`)",
            )
            it.execSQL("INSERT INTO collections (id, name, createdAt) VALUES (1, 'Inventario', 1)")
            it.execSQL(
                """
                INSERT INTO card_entries (
                    id, collectionId, game, name, setCode, setName, collectorNumber,
                    scryfallId, tcgplayerId, imageUrl, quantity, language, `condition`, foil,
                    priceEur, priceEurFoil, priceUsd, priceUsdFoil, priceCurrency, priceSource,
                    catalogName, catalogScryfallId, createdAt, variant, tcgdexId, copyKey
                ) VALUES (1,1,'MTG','Lightning Bolt','lea','Alpha','161','lea-161',null,null,1,
                    'English','Near Mint',1,'1.25','9.99',null,null,'EUR','Scryfall',null,null,1,null,null,'mtg')
                """.trimIndent(),
            )
            it.execSQL(
                """
                INSERT INTO card_entries (
                    id, collectionId, game, name, setCode, setName, collectorNumber,
                    scryfallId, tcgplayerId, imageUrl, quantity, language, `condition`, foil,
                    priceEur, priceEurFoil, priceUsd, priceUsdFoil, priceCurrency, priceSource,
                    catalogName, catalogScryfallId, createdAt, variant, tcgdexId, copyKey
                ) VALUES (2,1,'POKEMON','Pikachu','base1','Base','58',null,null,null,1,
                    'English','Near Mint',1,'2.00','8.00',null,null,'EUR','Cardmarket',null,null,2,null,'base1-58','pkm')
                """.trimIndent(),
            )
            it.version = 5
        }
    }

    private fun plantVersionFour(file: File) {
        file.parentFile?.mkdirs()
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        sqlite.use {
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `collections` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `card_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `collectionId` INTEGER NOT NULL,
                    `game` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `setCode` TEXT NOT NULL,
                    `setName` TEXT NOT NULL,
                    `collectorNumber` TEXT NOT NULL,
                    `scryfallId` TEXT,
                    `tcgplayerId` TEXT,
                    `imageUrl` TEXT,
                    `quantity` INTEGER NOT NULL,
                    `language` TEXT NOT NULL,
                    `condition` TEXT NOT NULL,
                    `foil` INTEGER NOT NULL,
                    `priceEur` TEXT,
                    `priceEurFoil` TEXT,
                    `priceUsd` TEXT,
                    `priceUsdFoil` TEXT,
                    `priceCurrency` TEXT,
                    `priceSource` TEXT,
                    `catalogName` TEXT,
                    `catalogScryfallId` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            it.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_card_entries_collectionId` ON `card_entries` (`collectionId`)",
            )
            it.execSQL("INSERT INTO collections (id, name, createdAt) VALUES (1, 'Inventario', 1)")
            insertCard(
                it,
                id = 1,
                name = "Teoria Perfezionata",
                scryfallId = "fra-34-it",
                catalogScryfallId = "fra-34",
                imageUrl = "it.jpg",
                priceEur = "1.20",
                quantity = 1,
                createdAt = 10,
            )
            insertCard(
                it,
                id = 2,
                name = "Perfected Theory",
                scryfallId = "fra-34",
                catalogScryfallId = null,
                imageUrl = null,
                priceEur = null,
                quantity = 2,
                createdAt = 20,
            )
            insertCard(
                it,
                id = 3,
                name = "Lightning Bolt",
                scryfallId = "lea-161",
                catalogScryfallId = null,
                imageUrl = null,
                priceEur = null,
                quantity = 4,
                createdAt = 5,
                setCode = "lea",
                collectorNumber = "161",
            )
            it.version = 4
        }
    }

    private fun insertCard(
        sqlite: SQLiteDatabase,
        id: Long,
        name: String,
        scryfallId: String,
        catalogScryfallId: String?,
        imageUrl: String?,
        priceEur: String?,
        quantity: Int,
        createdAt: Long,
        setCode: String = "fra",
        collectorNumber: String = "34",
    ) {
        sqlite.execSQL(
            """
            INSERT INTO card_entries (
                id, collectionId, game, name, setCode, setName, collectorNumber,
                scryfallId, tcgplayerId, imageUrl, quantity, language, `condition`, foil,
                priceEur, priceEurFoil, priceUsd, priceUsdFoil, priceCurrency, priceSource,
                catalogName, catalogScryfallId, createdAt
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(),
            arrayOf(
                id,
                1L,
                "MTG",
                name,
                setCode,
                "Foundations",
                collectorNumber,
                scryfallId,
                null,
                imageUrl,
                quantity.toLong(),
                "Italian",
                "Near Mint",
                0L,
                priceEur,
                null,
                null,
                null,
                null,
                null,
                null,
                catalogScryfallId,
                createdAt,
            ),
        )
    }
}
