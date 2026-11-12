package com.scantrader.app

import android.app.Application
import com.scantrader.app.data.db.AppDatabase
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.repo.CardRepository
import com.scantrader.app.data.repo.SingleCopyEditResult
import com.scantrader.app.ui.collection.CollectionBrowseState
import com.scantrader.app.ui.collection.browseCollection
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SingleCopyEditRepositoryTest {
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
    fun oneCopyChangesLanguageAndLeavesTheOtherCopiesAlone() = runBlocking {
        val db = AppDatabase.create(context)
        try {
            val repo = CardRepository(db)
            val collection = repo.createCollection("Inventario")
            val englishId = save(repo, collection, "161", CardLanguage.ENGLISH, 3)!!
            val italianId = save(repo, collection, "161", CardLanguage.ITALIAN, 1)!!
            repo.setManualPrice(repo.cardInCollection(collection, englishId)!!, "2.00")
            repo.setManualPrice(repo.cardInCollection(collection, italianId)!!, "9.00")

            val edited = repo.editSingleCopy(
                collectionId = collection,
                hostId = englishId,
                ownedQuantity = 1,
                language = CardLanguage.ITALIAN,
                condition = CardCondition.NEAR_MINT,
                foil = false,
                quantity = 1,
            )
            assertTrue(edited is SingleCopyEditResult.Saved)
            val rows = repo.cards(collection).first()
            val english = rows.single { it.language == CardLanguage.ENGLISH.csvValue }
            val italian = rows.single { it.language == CardLanguage.ITALIAN.csvValue }
            assertEquals(2, english.quantity)
            assertEquals("2.00", english.manualPriceEur)
            assertEquals(2, italian.quantity)
            assertEquals("9.00", italian.manualPriceEur)
            assertEquals(CardCondition.NEAR_MINT.csvValue, english.condition)
            assertEquals(italian.id, (edited as SingleCopyEditResult.Saved).hostId)
        } finally {
            db.close()
        }
    }

    @Test
    fun aMissingOrMovedRowIsNotEdited() = runBlocking {
        val db = AppDatabase.create(context)
        try {
            val repo = CardRepository(db)
            val source = repo.createCollection("Sorgente")
            val destination = repo.createCollection("Destinazione")
            val id = save(repo, source, "161", CardLanguage.ENGLISH, 2)!!
            val missing = repo.editSingleCopy(
                collectionId = source,
                hostId = 9_999L,
                ownedQuantity = 1,
                language = CardLanguage.ITALIAN,
                condition = CardCondition.NEAR_MINT,
                foil = false,
                quantity = 1,
            )
            assertTrue(missing is SingleCopyEditResult.Missing)
            assertEquals(2, repo.cards(source).first().single().quantity)

            val moved = repo.moveCards(
                sourceId = source,
                destinationId = destination,
                newCollectionName = null,
                requests = listOf(com.scantrader.app.data.db.CollectionMove.Request(id, 2)),
                mirrorId = null,
            )
            assertEquals(com.scantrader.app.data.repo.MoveCardsKind.Moved, moved.kind)
            val afterMove = repo.editSingleCopy(
                collectionId = source,
                hostId = id,
                ownedQuantity = 1,
                language = CardLanguage.ITALIAN,
                condition = CardCondition.NEAR_MINT,
                foil = false,
                quantity = 1,
            )
            assertTrue(afterMove is SingleCopyEditResult.Missing)
            val destinationRow = repo.cards(destination).first().single()
            assertEquals(2, destinationRow.quantity)
            assertEquals(CardLanguage.ENGLISH.csvValue, destinationRow.language)
        } finally {
            db.close()
        }
    }

    @Test
    fun bulkDeleteRemovesOnlyTheSelectedRowsAndUpdatesTheTotal() = runBlocking {
        val db = AppDatabase.create(context)
        try {
            val repo = CardRepository(db)
            val collection = repo.createCollection("Inventario")
            val other = repo.createCollection("Altra")
            val bolt = save(repo, collection, "161", CardLanguage.ENGLISH, 2)!!
            val bear = save(repo, collection, "162", CardLanguage.ENGLISH, 4)!!
            val kept = save(repo, collection, "163", CardLanguage.ENGLISH, 1)!!
            val elsewhere = save(repo, other, "161", CardLanguage.ENGLISH, 5)!!
            repo.setManualPrice(repo.cardInCollection(collection, bolt)!!, "1.50")
            repo.setManualPrice(repo.cardInCollection(collection, bear)!!, "3.00")
            repo.setManualPrice(repo.cardInCollection(collection, kept)!!, "4.00")

            val before = browseCollection(repo.cards(collection).first(), CollectionBrowseState())
            assertEquals(7, before.copies)
            assertEquals(0, BigDecimal("19.00").compareTo(before.total))

            val deleted = repo.deleteCards(collection, listOf(bolt, bear, elsewhere))
            assertEquals(2, deleted.rows)
            assertEquals(6, deleted.copies)
            val remaining = repo.cards(collection).first()
            assertEquals(listOf(kept), remaining.map { it.id })
            assertEquals(1, remaining.single().quantity)
            assertEquals(5, repo.cards(other).first().single().quantity)
            val after = browseCollection(remaining, CollectionBrowseState())
            assertEquals(1, after.copies)
            assertEquals(0, BigDecimal("4.00").compareTo(after.total))
        } finally {
            db.close()
        }
    }

    private suspend fun save(
        repo: CardRepository,
        collectionId: Long,
        number: String,
        language: CardLanguage,
        quantity: Int,
    ): Long? = repo.saveCard(
        collectionId,
        IdentifiedCard(
            game = Game.MTG,
            name = "Lightning Bolt",
            setCode = "lea",
            setName = "Alpha",
            collectorNumber = number,
            scryfallId = "lea-$number",
        ),
        quantity = quantity,
        language = language,
        condition = CardCondition.NEAR_MINT,
        foil = false,
    )
}
