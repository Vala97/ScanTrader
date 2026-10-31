package com.scantrader.app

import com.scantrader.app.vision.MtgCatalogStatus
import com.scantrader.app.vision.PokemonCatalogIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtgCatalogIndexTest {
    private val sha = "ab".repeat(32)

    private fun json(vararg rows: String) = """{"catalogs":[${rows.joinToString(",")}]}"""

    private fun row(id: String, version: String, url: String = "https://github.com/Vala97/ScanTrader/releases/download/stcat/$id.stcat") =
        """{"id":"$id","name":"$id","version":"$version","size":10,"sha256":"$sha","url":"$url"}"""

    @Test
    fun magicComesFromThePublishedIndexAndPokemonStaysSeparate() {
        val parsed = PokemonCatalogIndex.parse(
            json(
                row("pokemon-en", "2026.07.09"),
                row("mtg", "2026.07.09"),
                row("mtg-new", "2026.10.31"),
                row("http-only", "2026.1.1", url = "http://example.com/a.stcat"),
            ),
        )
        val magic = PokemonCatalogIndex.mtgEntry(parsed)
        assertEquals("mtg-new", magic?.id)
        assertEquals("2026.10.31", magic?.version)
        assertEquals(sha, magic?.sha256)
        val pokemon = parsed.filter { !PokemonCatalogIndex.isMtgCatalogId(it.id) }
        assertEquals(listOf("pokemon-en"), pokemon.map { it.id })
        assertNull(PokemonCatalogIndex.mtgEntry(pokemon))
    }

    @Test
    fun settingsShowTheCatalogDateAndAnAvailableUpdate() {
        assertEquals(
            "Data del catalogo non ancora letta.",
            MtgCatalogStatus().settingsLine(),
        )
        assertEquals(
            "Catalogo del 2026.10.31, da scaricare.",
            MtgCatalogStatus(remoteVersion = "2026.10.31").settingsLine(),
        )
        val installed = MtgCatalogStatus(
            installedVersion = "2026.07.09",
            remoteVersion = "2026.07.09",
            catalogInstalled = true,
        )
        assertEquals("Catalogo del 2026.07.09.", installed.settingsLine())
        assertFalse(installed.updateAvailable)
        val newer = installed.copy(remoteVersion = "2026.10.31")
        assertEquals("Catalogo del 2026.07.09. Disponibile 2026.10.31.", newer.settingsLine())
        assertTrue(newer.updateAvailable)
        val legacy = MtgCatalogStatus(remoteVersion = "2026.10.31", catalogInstalled = true)
        assertTrue(legacy.updateAvailable)
        assertEquals("Catalogo già sul telefono. Disponibile 2026.10.31.", legacy.settingsLine())
    }
}
