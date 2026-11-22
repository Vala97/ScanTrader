package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.vision.GalleryResidency
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryResidencyTest {
    @Test
    fun dropsEveryOtherGalleryIncludingCardTraderOnes() {
        val loaded = listOf(Game.MTG, Game.POKEMON, Game.ONE_PIECE, Game.YUGIOH)
        assertEquals(
            listOf(Game.MTG, Game.ONE_PIECE, Game.YUGIOH),
            GalleryResidency.toDrop(Game.POKEMON, loaded),
        )
        assertEquals(loaded, GalleryResidency.toDrop(Game.RIFTBOUND, loaded))
        assertEquals(emptyList<Game>(), GalleryResidency.toDrop(Game.MTG, listOf(Game.MTG)))
    }

    @Test
    fun changedCatalogReloadsOnlyForTheGameInUse() {
        assertTrue(GalleryResidency.reloadAfterChange(Game.POKEMON, Game.POKEMON))
        assertFalse(GalleryResidency.reloadAfterChange(Game.POKEMON, Game.MTG))
        assertFalse(GalleryResidency.reloadAfterChange(Game.POKEMON, null))
    }

    @Test
    fun warmGameDefaultsToMagic() {
        assertEquals(Game.MTG, GalleryResidency.warmGame(null))
        assertEquals(Game.MTG, GalleryResidency.warmGame("garbage"))
        assertEquals(Game.MTG, GalleryResidency.warmGame("OTHER"))
        assertEquals(Game.MTG, GalleryResidency.warmGame("RIFTBOUND"))
        assertEquals(Game.POKEMON, GalleryResidency.warmGame("POKEMON\n"))
        assertEquals(Game.ONE_PIECE, GalleryResidency.warmGame("ONE_PIECE"))
    }

    @Test
    fun scanGivesUpWhenTheLockStaysBusy() = runBlocking {
        val mutex = Mutex()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            mutex.lock()
            held.complete(Unit)
            release.await()
            mutex.unlock()
        }
        held.await()
        assertNull(GalleryResidency.lockedWithin(mutex, 50) { "gallery" })
        release.complete(Unit)
        holder.join()
        // The timed-out wait left the lock free.
        assertFalse(mutex.isLocked)
        assertEquals("gallery", GalleryResidency.lockedWithin(mutex, 50) { "gallery" })
        assertFalse(mutex.isLocked)
    }
}
