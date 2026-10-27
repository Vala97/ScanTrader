package com.scantrader.app

import android.app.Application
import com.scantrader.app.data.db.AppDatabase
import com.scantrader.app.data.export.CardTraderCsv
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.repo.CardRepository
import com.scantrader.app.data.repo.RenameCollection
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CollectionMaintenanceTest {
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
    fun renameChangesOnlyTheName() = runBlocking {
        val db = AppDatabase.create(context)
        try {
            val repo = CardRepository(db)
            val first = repo.createCollection("Uno")
            val second = repo.createCollection("Due")
            repo.saveCard(
                first,
                IdentifiedCard(
                    game = Game.MTG,
                    name = "Lightning Bolt",
                    setCode = "lea",
                    setName = "Alpha",
                    collectorNumber = "161",
                    scryfallId = "lea-161",
                    priceEur = "1.00",
                ),
                quantity = 2,
                language = CardLanguage.ITALIAN,
                condition = CardCondition.NEAR_MINT,
                foil = false,
            )
            assertEquals(RenameCollection.Empty, repo.renameCollection(first, "  "))
            assertEquals(RenameCollection.Duplicate, repo.renameCollection(first, " due "))
            assertEquals(RenameCollection.Saved, repo.renameCollection(first, "  Binder  "))
            val collections = repo.collectionList().first()
            assertEquals("Binder", collections.first { it.id == first }.name)
            assertEquals("Due", collections.first { it.id == second }.name)
            val cards = repo.cards(first).first()
            assertEquals(1, cards.size)
            assertEquals(first, cards.single().collectionId)
            assertEquals(2, cards.single().quantity)
            assertEquals("Lightning Bolt", cards.single().name)
        } finally {
            db.close()
        }
    }

    @Test
    fun manualPriceOverridesExportAndPokemonDropsFoil() = runBlocking {
        val db = AppDatabase.create(context)
        try {
            val repo = CardRepository(db)
            val id = repo.createCollection("Prezzi")
            repo.saveCard(
                id,
                IdentifiedCard(
                    game = Game.POKEMON,
                    name = "Pikachu",
                    setCode = "base1",
                    setName = "Base",
                    collectorNumber = "58",
                    tcgdexId = "base1-58",
                    priceEur = "1.25",
                    priceEurFoil = "9.99",
                ),
                quantity = 1,
                language = CardLanguage.ENGLISH,
                condition = CardCondition.NEAR_MINT,
                foil = true,
            )
            val stored = repo.cards(id).first().single()
            assertFalse(stored.foil)
            assertNull(stored.priceEurFoil)
            assertFalse(repo.setManualPrice(stored, "0"))
            assertTrue(repo.setManualPrice(stored, "2,5"))
            val priced = repo.cards(id).first().single()
            assertEquals("2.50", priced.manualPriceEur)
            assertEquals("1.25", priced.priceEur)
            assertNull(priced.priceEurFoil)
            assertEquals("250", CardTraderCsv.exportPriceCents(priced))
            assertTrue(repo.setManualPrice(priced, null))
            val restored = repo.cards(id).first().single()
            assertNull(restored.manualPriceEur)
            assertEquals("125", CardTraderCsv.exportPriceCents(restored))
        } finally {
            db.close()
        }
    }
}
