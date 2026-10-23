package com.scantrader.app

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.vision.mergeHybridCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryMergeTest {
    @Test
    fun mergesPokemonByTcgdexId() {
        val ocr = IdentifiedCard(
            game = Game.POKEMON,
            name = "Kadabra",
            setCode = "base1",
            setName = "Base",
            collectorNumber = "32",
            tcgdexId = "base1-32",
            ocrScore = 0.95f,
        )
        val gallery = IdentifiedCard(
            game = Game.POKEMON,
            name = "Kadabra",
            setCode = "base1",
            setName = "Base",
            collectorNumber = "32",
            tcgdexId = "base1-32",
            visualScore = 0.91f,
            ocrScore = 0.40f,
        )
        val merged = mergeHybridCandidates(listOf(ocr), listOf(gallery))
        assertEquals(1, merged.size)
        assertEquals("base1-32", merged[0].tcgdexId)
        assertEquals(0.91f, merged[0].visualScore)
        assertEquals(0.95f, merged[0].ocrScore)
    }

    @Test
    fun keepsGalleryHitWhenOcrMisses() {
        val gallery = IdentifiedCard(
            game = Game.POKEMON,
            name = "Kadabra",
            setCode = "base1",
            setName = "Base",
            collectorNumber = "32",
            tcgdexId = "base1-32",
            visualScore = 0.89f,
        )
        val merged = mergeHybridCandidates(emptyList(), listOf(gallery))
        assertEquals(1, merged.size)
        assertTrue((merged[0].visualScore ?: 0f) >= 0.89f)
    }
}
