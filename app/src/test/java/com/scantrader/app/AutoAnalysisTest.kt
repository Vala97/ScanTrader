package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.scan.AutoAnalysis
import com.scantrader.app.scan.AutoFrameCoordinator
import com.scantrader.app.scan.CatalogGalleryJoin
import com.scantrader.app.scan.FrameGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAnalysisTest {
    @Test
    fun strictNameSetCnDoesNotWaitForASlowGallery() = runBlocking {
        val signet = IdentifiedCard(
            game = Game.MTG,
            name = "Arcane Signet",
            setCode = "lcc",
            setName = "The Lost Caverns of Ixalan Commander",
            collectorNumber = "299",
        )
        val parsed = ParsedCardText(
            nameGuess = "Arcane Signet",
            collectorNumber = "299",
            setCode = "lcc",
        )
        assertTrue(AutoAnalysis.strictNameSetCn(listOf(signet), parsed))
        assertFalse(
            AutoAnalysis.strictNameSetCn(
                listOf(signet),
                parsed.copy(nameGuess = "Mind Stone"),
            ),
        )

        val cancelled = CompletableDeferred<Unit>()
        val started = System.currentTimeMillis()
        val gallery = coroutineScope {
            val job = async {
                suspendCancellableCoroutine<List<String>> { cont ->
                    cont.invokeOnCancellation { cancelled.complete(Unit) }
                }
            }
            yield()
            val hydrated = CatalogGalleryJoin.afterCatalog(
                strict = true,
                gallery = { job.await() },
                cancelGallery = { job.cancel() },
            )
            hydrated
        }
        assertTrue(gallery.isEmpty())
        cancelled.await()
        assertTrue(System.currentTimeMillis() - started < 1_000)
    }

    @Test
    fun galleryHydrateTimesOutAndCancels() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val started = System.currentTimeMillis()
        val hydrated = coroutineScope {
            val job = async {
                try {
                    delay(10_000)
                    listOf("late")
                } finally {
                    if (!coroutineContext.isActive) cancelled.complete(Unit)
                }
            }
            CatalogGalleryJoin.afterCatalog(
                strict = false,
                timeoutMs = 80,
                gallery = { job.await() },
                cancelGallery = { job.cancel() },
            )
        }
        assertTrue(hydrated.isEmpty())
        cancelled.await()
        assertTrue(System.currentTimeMillis() - started < 1_000)
    }

    @Test
    fun staleResultIsDiscardedWhenTheFrameChanges() {
        var now = 1_000L
        val frames = AutoFrameCoordinator(busyCapMs = 8_000L, now = { now })
        val first = frames.start(now)
        assertFalse(frames.canAccept(now + 100))
        assertTrue(frames.noteSceneChanged(FrameGate.MOTION_MAX + 1.0))
        assertFalse(frames.finish(first))
        assertTrue(frames.canAccept(now + 100))
        val second = frames.start(now + 100)
        assertTrue(second != first)
        assertTrue(frames.finish(second))
    }

    @Test
    fun busyCapLetsTheNextFrameStartAndDropsTheHungResult() {
        var now = 5_000L
        val frames = AutoFrameCoordinator(busyCapMs = 1_000L, now = { now })
        val hung = frames.start(now)
        assertFalse(frames.canAccept(now + 500))
        now += 1_000
        assertTrue(frames.canAccept(now))
        val next = frames.start(now)
        assertFalse(frames.finish(hung))
        assertTrue(frames.finish(next))
        assertEquals("autoResult stale discarded gen=$hung", AutoAnalysis.staleLog(hung))
    }
}
