package com.scantrader.app

import android.app.Application
import com.scantrader.app.data.model.Game
import com.scantrader.app.vision.EmbeddingCatalog
import com.scantrader.app.vision.NeuralPackManager
import com.scantrader.app.vision.NeuralPackState
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class NeuralPackResidencyTest {
    private lateinit var vision: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        vision = File(context.filesDir, "vision").apply {
            deleteRecursively()
            mkdirs()
        }
        File(vision, "milo.onnx").writeBytes(byteArrayOf(1))
        catalog("mtg-1").save(File(vision, "mtg.stcat"))
        catalog("base1-1").save(File(vision, "pokemon.stcat"))
        catalog("op01-001").save(File(vision, "ct-onepiece.stcat"))
    }

    private fun manager() =
        NeuralPackManager(RuntimeEnvironment.getApplication(), OkHttpClient(), openSession = false)

    private fun catalog(id: String): EmbeddingCatalog {
        val dim = EmbeddingCatalog.EMBED_DIM
        return EmbeddingCatalog.create(arrayOf(id), FloatArray(dim) { if (it == 0) 1f else 0f }, dim)
    }

    private fun query() = FloatArray(EmbeddingCatalog.EMBED_DIM) { if (it == 0) 1f else 0f }

    @Test
    fun choosingAGameDropsTheOtherGallery() = runBlocking {
        val pack = manager()
        assertTrue(pack.useOnly(Game.MTG))
        assertNotNull(pack.catalogOrNull(Game.MTG))
        assertNull(pack.catalogOrNull(Game.POKEMON))

        assertTrue(pack.useOnly(Game.POKEMON))
        assertNull(pack.catalogOrNull(Game.MTG))
        assertEquals(NeuralPackState.InstalledNotLoaded, pack.mtgState.value)
        assertEquals("base1-1", pack.catalogOrNull(Game.POKEMON)?.cardIds?.single())
        assertEquals(Game.POKEMON, pack.activeGame)
    }

    @Test
    fun cardTraderGalleriesAreAlsoOneAtATime() = runBlocking {
        val pack = manager()
        assertTrue(pack.useOnly(Game.POKEMON))
        assertTrue(pack.useOnly(Game.ONE_PIECE))
        assertNull(pack.catalogOrNull(Game.POKEMON))
        assertNotNull(pack.catalogOrNull(Game.ONE_PIECE))

        // No Yu-Gi-Oh! gallery on disk: One Piece is dropped anyway.
        assertFalse(pack.useOnly(Game.YUGIOH))
        assertNull(pack.catalogOrNull(Game.ONE_PIECE))

        assertTrue(pack.useOnly(Game.MTG))
        assertFalse(pack.useOnly(Game.RIFTBOUND))
        assertNull(pack.catalogOrNull(Game.MTG))
    }

    @Test
    fun pokemonCatalogChangeWhileOnMagicDoesNotLoadIt() = runBlocking {
        val pack = manager()
        assertTrue(pack.useOnly(Game.MTG))
        assertTrue(pack.reloadPokemonCatalog())
        assertNull(pack.catalogOrNull(Game.POKEMON))
        assertNotNull(pack.catalogOrNull(Game.MTG))
        assertEquals(NeuralPackState.InstalledNotLoaded, pack.pokemonState.value)

        assertTrue(pack.useOnly(Game.POKEMON))
        assertTrue(pack.reloadPokemonCatalog())
        assertNotNull(pack.catalogOrNull(Game.POKEMON))
    }

    @Test
    fun startupWarmsOnlyTheLastGame() = runBlocking {
        assertEquals(Game.MTG, manager().lastGame())
        assertTrue(manager().useOnly(Game.POKEMON))

        val restarted = manager()
        assertEquals(Game.POKEMON, restarted.lastGame())
        assertTrue(restarted.ensureLoaded())
        assertNotNull(restarted.catalogOrNull(Game.POKEMON))
        assertNull(restarted.catalogOrNull(Game.MTG))
    }

    @Test
    fun scanKeepsItsGalleryWhenTheGameChanges() = runBlocking {
        val pack = manager()
        val inScan = pack.catalogWithin(Game.MTG, 2_000)
        assertNotNull(inScan)
        assertTrue(pack.useOnly(Game.POKEMON))
        assertNull(pack.catalogOrNull(Game.MTG))
        // The reference held by the scan still answers.
        assertEquals("mtg-1", inScan!!.search(query(), topK = 1).single().cardId)
    }
}
