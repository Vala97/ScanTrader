package com.scantrader.app

import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CollectionEntity
import com.scantrader.app.data.export.BackupCard
import com.scantrader.app.data.export.BackupCollection
import com.scantrader.app.data.export.CollectionBackup
import com.scantrader.app.data.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CollectionBackupTest {
    @Test
    fun roundTripPreservesOptionalNulls() {
        val entities = listOf(
            CollectionEntity(id = 1, name = "Inventario", createdAt = 10L),
            CollectionEntity(id = 2, name = "Binder", createdAt = 20L),
        )
        val cards = mapOf(
            1L to listOf(
                CardEntryEntity(
                    id = 11,
                    collectionId = 1,
                    game = Game.MTG,
                    name = "Lightning Bolt",
                    setCode = "lea",
                    setName = "Limited Edition Alpha",
                    collectorNumber = "161",
                    scryfallId = "abc",
                    tcgplayerId = null,
                    imageUrl = "https://example/img",
                    quantity = 2,
                    language = "English",
                    condition = "Near Mint",
                    foil = false,
                    priceEur = "1.50",
                    priceEurFoil = null,
                    catalogName = "Lightning Bolt",
                    catalogScryfallId = "abc",
                    variant = "frame:showcase",
                    manualPriceEur = "3.00",
                    copyKey = "x",
                ),
            ),
            2L to listOf(
                CardEntryEntity(
                    id = 22,
                    collectionId = 2,
                    game = Game.POKEMON,
                    name = "Pikachu",
                    setCode = "base1",
                    setName = "Base",
                    collectorNumber = "58",
                    scryfallId = null,
                    tcgplayerId = "123",
                    imageUrl = null,
                    quantity = 1,
                    language = "Italian",
                    condition = "Slightly Played",
                    foil = false,
                    tcgdexId = "base1-58",
                    copyKey = "y",
                ),
            ),
        )
        val backup = CollectionBackup.fromEntities(entities, cards, now = 99L)
        val parsed = CollectionBackup.parse(CollectionBackup.toJson(backup))
        assertEquals(1, parsed.version)
        assertEquals(99L, parsed.exportedAt)
        assertEquals(2, parsed.collections.size)
        assertEquals("Inventario", parsed.collections[0].name)
        assertEquals("Lightning Bolt", parsed.collections[0].cards[0].name)
        assertEquals("frame:showcase", parsed.collections[0].cards[0].variant)
        assertEquals("3.00", parsed.collections[0].cards[0].manualPriceEur)
        assertNull(parsed.collections[0].cards[0].tcgplayerId)
        assertEquals("Pikachu", parsed.collections[1].cards[0].name)
        assertEquals("base1-58", parsed.collections[1].cards[0].tcgdexId)
        val restored = parsed.collections[0].cards[0].toEntity(collectionId = 7)
        assertEquals(7L, restored.collectionId)
        assertEquals(0L, restored.id)
        assertTrue(restored.copyKey.isNotBlank())
    }

    @Test
    fun uniqueImportNameAvoidsCollisions() {
        assertEquals("Binder", CollectionBackup.uniqueImportName("Binder", emptySet()))
        assertEquals("Binder (import)", CollectionBackup.uniqueImportName("Binder", setOf("Binder")))
        assertEquals(
            "Binder (import 2)",
            CollectionBackup.uniqueImportName("Binder", setOf("Binder", "Binder (import)")),
        )
        assertEquals(
            "binder (import)",
            CollectionBackup.uniqueImportName("binder", setOf("BINDER")),
        )
    }

    @Test
    fun rejectsInvalidJsonAndWrongVersion() {
        try {
            CollectionBackup.parse("{not json")
            fail("expected parse error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non valido"))
        }
        try {
            CollectionBackup.parse("""{"version":2,"exportedAt":1,"collections":[]}""")
            fail("expected version error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Versione"))
        }
    }

    @Test
    fun backupCardDropsIds() {
        val card = BackupCard.from(
            CardEntryEntity(
                id = 99,
                collectionId = 5,
                game = Game.MTG,
                name = "Bolt",
                setCode = "m21",
                setName = "Core",
                collectorNumber = "1",
                scryfallId = null,
                tcgplayerId = null,
                imageUrl = null,
                quantity = 1,
                language = "English",
                condition = "Near Mint",
                foil = true,
            ),
        )
        val json = CollectionBackup.toJson(
            CollectionBackup(
                collections = listOf(BackupCollection(name = "A", createdAt = 1L, cards = listOf(card))),
            ),
        )
        assertTrue(!json.contains("\"id\""))
        assertTrue(!json.contains("collectionId"))
    }
}
