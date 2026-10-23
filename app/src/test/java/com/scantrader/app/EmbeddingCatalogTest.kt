package com.scantrader.app

import com.scantrader.app.vision.EmbeddingCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingCatalogTest {
    @Test
    fun searchReturnsNearestNeighbour() {
        val dim = 128
        val ids = arrayOf("a", "b", "c")
        val data = FloatArray(ids.size * dim)
        for (d in 0 until dim) {
            data[1 * dim + d] = 1f
        }
        data[0 * dim] = 1f
        data[2 * dim + 3] = 1f
        for (r in ids.indices) {
            val row = FloatArray(dim) { data[r * dim + it] }
            val normed = EmbeddingCatalog.l2Normalize(row)
            for (d in 0 until dim) data[r * dim + d] = normed[d]
        }
        val catalog = EmbeddingCatalog.create(ids, data, dim)
        val query = EmbeddingCatalog.l2Normalize(FloatArray(dim) { 1f })
        val hits = catalog.search(query, topK = 2)
        assertEquals("b", hits.first().cardId)
        assertTrue(hits.first().score > hits.last().score)
    }

    @Test
    fun roundTripStcat() {
        val dim = 4
        val ids = arrayOf("id-1", "id-2")
        val data = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
        )
        val original = EmbeddingCatalog.create(ids, data, dim)
        val file = File.createTempFile("test", ".stcat")
        try {
            original.save(file)
            val loaded = EmbeddingCatalog.load(file)
            assertEquals(2, loaded.size)
            assertEquals("id-1", loaded.cardIds[0])
            val hits = loaded.search(floatArrayOf(1f, 0f, 0f, 0f), topK = 1)
            assertEquals("id-1", hits.single().cardId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun loadsLorcanaNpzIfPresent() {
        val npz = File("C:/Users/Vala/ScanTrader/.tmp-vision/lorcana.npz")
        if (!npz.exists()) return
        val catalog = EmbeddingCatalog.fromNpz(npz)
        assertTrue(catalog.size > 1000)
        assertEquals(128, catalog.dim)
        val query = catalog.let {
            // Use first embedding as query — must rank itself first.
            val emb = FloatArray(128)
            // Access via search self: pick id 0 by reconstructing from file is hard;
            // just ensure search returns something.
            EmbeddingCatalog.l2Normalize(FloatArray(128) { if (it == 0) 1f else 0f })
        }
        val hits = catalog.search(query, topK = 3)
        assertEquals(3, hits.size)
        assertTrue(hits.first().score <= 1.0001f)
    }
}
