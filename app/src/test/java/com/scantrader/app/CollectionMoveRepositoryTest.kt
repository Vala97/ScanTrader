package com.scantrader.app

import android.app.Application
import com.scantrader.app.data.db.AppDatabase
import com.scantrader.app.data.db.CollectionMove
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.repo.CardRepository
import com.scantrader.app.data.repo.MoveCardsKind
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CollectionMoveRepositoryTest {
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
    fun noMirrorCreatesTheNewCollectionAndKeepsEveryCopy() = runBlocking {
        val db = AppDatabase.create(context)
        try {
            val repo = CardRepository(db)
            val source = repo.createCollection("Sorgente")
            save(repo, source, quantity = 4)
            val row = repo.cards(source).first().single()
            val result = repo.moveCards(
                sourceId = source,
                destinationId = null,
                newCollectionName = "Nuova",
                requests = listOf(CollectionMove.Request(row.id, 4)),
                mirrorId = null,
            )
            assertEquals(MoveCardsKind.Moved, result.kind)
            assertEquals(4, result.copies)
            val created = repo.collectionList().first().single { it.name == "Nuova" }
            assertEquals(4, repo.cards(created.id).first().sumOf { it.quantity })
            assertEquals(0, repo.cards(source).first().sumOf { it.quantity })
        } finally {
            db.close()
        }
    }

    @Test
    fun noMirrorStillMovesIntoAnExistingCollection() = runBlocking {
        val db = AppDatabase.create(context)
        try {
            val repo = CardRepository(db)
            val source = repo.createCollection("Sorgente")
            val destination = repo.createCollection("Destinazione")
            save(repo, source, quantity = 5)
            val row = repo.cards(source).first().single()
            val result = repo.moveCards(
                sourceId = source,
                destinationId = destination,
                newCollectionName = null,
                requests = listOf(CollectionMove.Request(row.id, 2)),
                mirrorId = null,
            )
            assertEquals(MoveCardsKind.Moved, result.kind)
            assertEquals(2, result.copies)
            assertEquals(3, repo.cards(source).first().sumOf { it.quantity })
            assertEquals(2, repo.cards(destination).first().sumOf { it.quantity })
            assertEquals(2, repo.collectionList().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun aRealMirrorCannotSendOrReceive() = runBlocking {
        val db = AppDatabase.create(context)
        try {
            val repo = CardRepository(db)
            val source = repo.createCollection("Sorgente")
            val mirror = repo.createCollection("Cardtrader")
            val other = repo.createCollection("Altra")
            save(repo, source, quantity = 3)
            save(repo, mirror, quantity = 1)
            val sourceRow = repo.cards(source).first().single()
            val mirrorRow = repo.cards(mirror).first().single()
            val blockedSource = repo.moveCards(
                sourceId = mirror,
                destinationId = other,
                newCollectionName = null,
                requests = listOf(CollectionMove.Request(mirrorRow.id, 1)),
                mirrorId = mirror,
            )
            val blockedDestination = repo.moveCards(
                sourceId = source,
                destinationId = mirror,
                newCollectionName = null,
                requests = listOf(CollectionMove.Request(sourceRow.id, 3)),
                mirrorId = mirror,
            )
            assertEquals(MoveCardsKind.MirrorBlocked, blockedSource.kind)
            assertEquals(MoveCardsKind.MirrorBlocked, blockedDestination.kind)
            assertEquals(1, repo.cards(mirror).first().sumOf { it.quantity })
            assertEquals(3, repo.cards(source).first().sumOf { it.quantity })
            assertEquals(0, repo.cards(other).first().sumOf { it.quantity })
        } finally {
            db.close()
        }
    }

    @Test
    fun aNewCollectionWithNothingToMoveIsRemoved() = runBlocking {
        val db = AppDatabase.create(context)
        try {
            val repo = CardRepository(db)
            val source = repo.createCollection("Sorgente")
            save(repo, source, quantity = 2)
            val row = repo.cards(source).first().single()
            val result = repo.moveCards(
                sourceId = source,
                destinationId = null,
                newCollectionName = "Vuota",
                requests = listOf(CollectionMove.Request(row.id, 0)),
                mirrorId = null,
            )
            assertEquals(MoveCardsKind.NothingToMove, result.kind)
            assertFalse(repo.collectionList().first().any { it.name == "Vuota" })
            assertEquals(2, repo.cards(source).first().sumOf { it.quantity })
        } finally {
            db.close()
        }
    }

    private suspend fun save(repo: CardRepository, collectionId: Long, quantity: Int) {
        repo.saveCard(
            collectionId,
            IdentifiedCard(
                game = Game.MTG,
                name = "Lightning Bolt",
                setCode = "lea",
                setName = "Alpha",
                collectorNumber = "161",
                scryfallId = "lea-161",
            ),
            quantity = quantity,
            language = CardLanguage.ITALIAN,
            condition = CardCondition.NEAR_MINT,
            foil = false,
        )
    }
}
