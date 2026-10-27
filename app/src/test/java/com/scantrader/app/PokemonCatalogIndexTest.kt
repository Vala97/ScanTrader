package com.scantrader.app

import com.scantrader.app.vision.EmbeddingCatalog
import com.scantrader.app.vision.PokemonCatalogIndex
import com.scantrader.app.vision.PokemonCatalogLibrary
import com.scantrader.app.vision.PokemonCatalogRow
import com.scantrader.app.vision.StcatCatalogEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PokemonCatalogIndexTest {
    @Test
    fun parsesTheIndexAndSkipsIncompleteEntries() {
        val sha = "ab".repeat(32)
        val json = """
            {
              "catalogs": [
                {
                  "id": "pokemon-en",
                  "name": "Pokémon (inglese)",
                  "version": "2026.10.26",
                  "size": 13000000,
                  "sha256": "$sha",
                  "url": "https://github.com/Vala97/ScanTrader/releases/download/stcat/pokemon-en.stcat"
                },
                {"id": "broken", "name": "manca il resto"},
                {"id": "bad-hash", "sha256": "zz", "url": "https://example.com/a.stcat", "version": "1"},
                {"name": "senza id", "sha256": "$sha", "url": "https://example.com/b.stcat"},
                {"id": "../etc", "sha256": "$sha", "url": "https://example.com/c.stcat"},
                {"id": "http-only", "sha256": "$sha", "url": "http://example.com/d.stcat", "version": "1"}
              ]
            }
        """.trimIndent()
        val parsed = PokemonCatalogIndex.parse(json)
        assertEquals(1, parsed.size)
        val only = parsed.single()
        assertEquals("pokemon-en", only.id)
        assertEquals("Pokémon (inglese)", only.name)
        assertEquals("2026.10.26", only.version)
        assertEquals(13_000_000L, only.size)
        assertEquals(sha, only.sha256)
        assertTrue(only.url.endsWith("/pokemon-en.stcat"))
    }

    @Test
    fun emptyOrBrokenJsonIsAnEmptyList() {
        assertTrue(PokemonCatalogIndex.parse("").isEmpty())
        assertTrue(PokemonCatalogIndex.parse("{").isEmpty())
        assertTrue(PokemonCatalogIndex.parse("""{"catalogs":{}}""").isEmpty())
    }

    @Test
    fun sha256MatchesKnownDigests() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            PokemonCatalogIndex.sha256(ByteArray(0)),
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            PokemonCatalogIndex.sha256("hello".toByteArray(Charsets.UTF_8)),
        )
        val file = File.createTempFile("sha", ".bin")
        try {
            file.writeBytes("hello".toByteArray(Charsets.UTF_8))
            assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                PokemonCatalogIndex.sha256(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun dottedVersionsCompareNumerically() {
        assertTrue(PokemonCatalogIndex.compareVersions("1.2", "1.1") > 0)
        assertTrue(PokemonCatalogIndex.compareVersions("1.10", "1.9") > 0)
        assertTrue(PokemonCatalogIndex.compareVersions("2026.10.26", "2026.9.1") > 0)
        assertEquals(0, PokemonCatalogIndex.compareVersions("1.2", "1.2.0"))
        assertTrue(PokemonCatalogIndex.compareVersions("1.0", "1.2") < 0)
        assertFalse(PokemonCatalogIndex.isUpdate("1.2", null, fileExists = false))
        assertFalse(PokemonCatalogIndex.isUpdate("1.0", "1.0", fileExists = true))
        assertFalse(PokemonCatalogIndex.isUpdate("1.0", "1.2", fileExists = true))
        assertTrue(PokemonCatalogIndex.isUpdate("1.2", "1.1", fileExists = true))
        assertTrue(PokemonCatalogIndex.isUpdate("2026.10.26", "", fileExists = true))
        assertTrue(PokemonCatalogIndex.formatBytes(13_000_000L).contains("12,4"))
    }

    @Test
    fun checksumMismatchLeavesTheInstalledCatalogUntouched() {
        val dir = File.createTempFile("stcat-lib", "").apply {
            delete()
            mkdirs()
        }
        try {
            val library = PokemonCatalogLibrary(dir)
            val first = tiny("old-card")
            val firstFile = File(dir, "first.stcat")
            first.save(firstFile)
            val firstHash = PokemonCatalogIndex.sha256(firstFile)
            val installed = entry("1.0", firstHash, firstFile.length())
            library.installVerified(installed, firstFile)
            val dest = library.catalogFile("pokemon-en")
            val before = dest.readBytes()
            assertEquals("1.0", library.read("pokemon-en")?.version)
            assertEquals(1, library.read("pokemon-en")?.cardCount)
            assertEquals(1, EmbeddingCatalog.load(dest).size)

            val garbage = "not-a-catalog".toByteArray()
            val bad = File(dir, "bad.part")
            bad.writeBytes(garbage)
            val wrongHash = entry("2.0", "ab".repeat(32), garbage.size.toLong())
            val checksum = assertThrows(IllegalStateException::class.java) {
                library.installVerified(wrongHash, bad)
            }
            assertTrue(checksum.message!!.contains("checksum"))
            assertFalse(bad.exists())
            assertTrue(before.contentEquals(dest.readBytes()))
            assertEquals("1.0", library.read("pokemon-en")?.version)

            val corrupt = File(dir, "corrupt.part")
            corrupt.writeBytes(garbage)
            val matching = entry("9.0", PokemonCatalogIndex.sha256(garbage), garbage.size.toLong())
            val invalid = assertThrows(Exception::class.java) {
                library.installVerified(matching, corrupt)
            }
            assertFalse(invalid.message.orEmpty().contains("checksum"))
            assertFalse(corrupt.exists())
            assertTrue(before.contentEquals(dest.readBytes()))
            assertEquals("1.0", library.read("pokemon-en")?.version)

            val manual = File(dir, "pokemon.stcat")
            manual.writeBytes(before)
            library.delete("pokemon-en")
            assertNull(library.read("pokemon-en"))
            assertFalse(dest.exists())
            assertTrue(manual.exists())
            assertTrue(library.hasAny())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun statusLineMatchesThePackWording() {
        val ready = sampleRow().copy(
            installed = true,
            installedVersion = "2026.10.26",
            installedSize = 13_000_000L,
            cardCount = 1200,
        )
        assertEquals(
            "Pronto · 1200 carte · versione 2026.10.26 · 12,4 MB",
            ready.statusLine(),
        )
        val missing = sampleRow()
        assertEquals(
            "Non installato · versione 2026.10.26 · 12,4 MB",
            missing.statusLine(),
        )
        val update = ready.copy(remoteVersion = "2026.11.1", updateAvailable = true)
        assertTrue(update.statusLine().contains("versione 2026.10.26 → 2026.11.1"))
        assertEquals("Download… (40%)", ready.copy(downloading = true, progress = 0.4f).statusLine())
    }

    private fun sampleRow() = PokemonCatalogRow(
        id = "pokemon-en",
        name = "Pokémon (inglese)",
        remoteVersion = "2026.10.26",
        remoteSize = 13_000_000L,
        installedVersion = null,
        installedSize = null,
        installed = false,
        updateAvailable = false,
        downloading = false,
        progress = 0f,
        error = null,
    )

    private fun tiny(id: String): EmbeddingCatalog {
        return EmbeddingCatalog.create(arrayOf(id), floatArrayOf(1f, 0f, 0f, 0f), 4)
    }

    private fun entry(version: String, sha: String, size: Long) = StcatCatalogEntry(
        id = "pokemon-en",
        name = "Pokémon (inglese)",
        version = version,
        size = size,
        sha256 = sha,
        url = "https://github.com/Vala97/ScanTrader/releases/download/stcat/pokemon-en.stcat",
    )
}
