package com.scantrader.app

import com.scantrader.app.vision.EmbeddingCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue

class EmbeddingCatalogStcatTest {
    @Test
    fun roundTripSaveLoadAndSearch() {
        val ids = arrayOf("base1-32", "base1-33")
        val dim = EmbeddingCatalog.EMBED_DIM
        val emb = FloatArray(ids.size * dim) { i ->
            val row = i / dim
            val col = i % dim
            if (col == row) 1f else 0f
        }
        // L2-normalize rows roughly (already sparse unit-ish).
        val catalog = EmbeddingCatalog.create(ids, emb, dim)
        val tmp = File.createTempFile("test", ".stcat")
        try {
            catalog.save(tmp)
            val loaded = EmbeddingCatalog.load(tmp)
            assertEquals(2, loaded.size)
            val query = FloatArray(dim) { if (it == 0) 1f else 0f }
            val hits = loaded.search(EmbeddingCatalog.l2Normalize(query), topK = 2)
            assertEquals("base1-32", hits.first().cardId)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun loadsSmokePokemonStcatIfPresent() {
        val smoke = File("../tools/build_pokemon_stcat/pokemon-smoke.stcat")
            .takeIf { it.isFile }
            ?: File("tools/build_pokemon_stcat/pokemon-smoke.stcat")
        assumeTrue("Run tools/build_pokemon_stcat smoke first", smoke.isFile)
        val loaded = EmbeddingCatalog.load(smoke)
        assertTrue(loaded.size >= 1)
        assertTrue(loaded.cardIds.any { it.startsWith("base1-") })
        assertEquals(EmbeddingCatalog.EMBED_DIM, loaded.dim)
    }
}
