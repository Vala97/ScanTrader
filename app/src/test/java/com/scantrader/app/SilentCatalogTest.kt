package com.scantrader.app

import com.scantrader.app.vision.CatalogFiles
import com.scantrader.app.vision.CatalogStatusText
import com.scantrader.app.vision.CatalogUpdateRules
import com.scantrader.app.vision.PokemonCatalogIndex
import com.scantrader.app.vision.SilentCatalogStore
import com.scantrader.app.vision.StagedCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentCatalogTest {
    @Test
    fun aNewerIndexVersionIsAnUpdateAndAMissingCatalogIsNot() {
        assertTrue(CatalogUpdateRules.shouldDownload(true, "2026.07.09", "2026.11.16"))
        assertFalse(CatalogUpdateRules.shouldDownload(true, "2026.11.16", "2026.11.16"))
        assertFalse(CatalogUpdateRules.shouldDownload(false, null, "2026.11.16"))
        assertTrue(PokemonCatalogIndex.compareVersions("2026.11.16", "2026.07.09") > 0)
    }

    @Test
    fun aBadChecksumDoesNotReplaceTheLiveFile() {
        val dir = tempDir()
        val live = File(dir, "mtg.stcat").apply { writeText("old-catalog") }
        val staged = File(dir, "staged.stcat").apply { writeText("new-catalog") }
        val hash = CatalogFiles.sha256(staged)
        assertFalse(
            CatalogUpdateRules.payloadMatches(hash, "ab".repeat(32), staged.length(), staged.length()),
        )
        assertEquals("old-catalog", live.readText())
        staged.delete()
        assertEquals("old-catalog", live.readText())
    }

    @Test
    fun aVerifiedFileSwapsIntoPlaceAndAMissingStageDoesNot() {
        val dir = tempDir()
        val live = File(dir, "mtg.stcat").apply { writeText("old-catalog") }
        val staged = File(dir, "staged.stcat").apply { writeText("new-catalog") }
        val hash = CatalogFiles.sha256(staged)
        assertTrue(CatalogUpdateRules.payloadMatches(hash, hash, staged.length(), staged.length()))
        assertTrue(CatalogFiles.swap(live, staged))
        assertEquals("new-catalog", live.readText())
        assertFalse(staged.exists())
        val gone = File(dir, "missing.stcat")
        assertFalse(CatalogFiles.swap(live, gone))
        assertEquals("new-catalog", live.readText())
    }

    @Test
    fun settingsNameTheDateAndTheSetWhenTheIndexHasOne() {
        assertEquals(
            "Magic: aggiornato il 16/11/2026 · ultimo set: Foundations",
            CatalogStatusText.line("Magic", "2026-11-16", "Foundations"),
        )
        assertEquals(
            "Pokémon: aggiornato il 09/07/2026",
            CatalogStatusText.line("Pokémon", "2026.7.9", null),
        )
        assertEquals(
            "Pokémon: aggiornato il 09/07/2026",
            CatalogStatusText.line("Pokémon", "2026.7.9", "  "),
        )
        assertNull(CatalogStatusText.line("Magic", null, "Foundations"))
    }

    @Test
    fun activatingAPendingCatalogReplacesTheLiveOne() {
        val dir = tempDir()
        File(dir, "mtg.stcat").writeText("old-catalog")
        File(dir, "mtg.version").writeText("2026.07.09")
        val store = SilentCatalogStore(dir)
        val staged = File(dir, "incoming.stcat").apply { writeText("new-catalog") }
        store.stage(
            "mtg",
            staged,
            StagedCatalog(
                version = "2026.11.16",
                sha256 = "ab".repeat(32),
                size = staged.length(),
                latestSetName = "Foundations",
                updatedAt = "2026-11-16",
            ),
        )
        assertEquals("old-catalog", File(dir, "mtg.stcat").readText())
        assertEquals(setOf("mtg"), store.activate())
        assertEquals("new-catalog", File(dir, "mtg.stcat").readText())
        assertEquals("2026.11.16", File(dir, "mtg.version").readText())
        assertEquals(
            "Magic: aggiornato il 16/11/2026 · ultimo set: Foundations",
            store.lines().magic,
        )
    }

    @Test
    fun optionalIndexFieldsDoNotHideTheRequiredOnes() {
        val sha = "ab".repeat(32)
        val parsed = PokemonCatalogIndex.parse(
            """
            {"catalogs":[{
              "id":"mtg","name":"Magic","version":"2026.11.16","size":4,
              "sha256":"$sha",
              "url":"https://github.com/Vala97/ScanTrader/releases/download/stcat/milo.npz",
              "latest_set_name":"Foundations",
              "latest_set_code":"fdn",
              "latest_set_date":"2026-11-01",
              "updated_at":"2026-11-16"
            }]}
            """.trimIndent(),
        ).single()
        assertEquals("mtg", parsed.id)
        assertEquals("Foundations", parsed.latestSetName)
        assertEquals("2026-11-16", parsed.updatedAt)
    }

    private fun tempDir(): File = File.createTempFile("catalog", "").apply {
        delete()
        mkdirs()
    }
}
