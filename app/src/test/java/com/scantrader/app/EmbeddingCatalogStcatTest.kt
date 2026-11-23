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
    fun bulkLoadReadsTheSameValuesAcrossChunks() {
        // 300 x 128 floats = 150 KB: several 64 KB chunks and a partial one.
        val rows = 300
        val dim = EmbeddingCatalog.EMBED_DIM
        fun value(i: Int) = ((i * 37) % 1001 - 500) / 97f
        val tmp = File.createTempFile("bulk", ".stcat")
        try {
            java.io.DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.write("STCAT10\u0000".toByteArray(Charsets.US_ASCII))
                out.writeInt(1)
                out.writeInt(rows)
                out.writeInt(dim)
                for (i in 0 until rows * dim) out.writeFloat(value(i))
                for (r in 0 until rows) {
                    val bytes = "id-$r".toByteArray(Charsets.UTF_8)
                    out.writeShort(bytes.size)
                    out.write(bytes)
                }
            }
            val loaded = EmbeddingCatalog.load(tmp)
            assertEquals(rows, loaded.size)
            assertEquals("id-299", loaded.cardIds.last())
            for (col in listOf(0, 1, 63, 127)) {
                val query = FloatArray(dim) { if (it == col) 1f else 0f }
                val scores = loaded.search(query, topK = rows).associate { it.cardId to it.score }
                for (r in 0 until rows) {
                    assertEquals(value(r * dim + col), scores.getValue("id-$r"), 0f)
                }
            }
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
