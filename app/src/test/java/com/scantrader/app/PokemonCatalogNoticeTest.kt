package com.scantrader.app

import com.scantrader.app.vision.PokemonCatalogNotice
import com.scantrader.app.vision.StcatCatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PokemonCatalogNoticeTest {
    @Test
    fun readsTheEnglishPokemonRow() {
        val entries = listOf(
            StcatCatalogEntry("mtg", "2026.11.13", "https://example/mtg", "aa", 1L),
            StcatCatalogEntry("pokemon-en", "2026.10.26", "https://example/p", "bb", 2L),
        )
        assertEquals("2026.10.26", PokemonCatalogNotice.versionOf(entries))
    }

    @Test
    fun ignoresAMissingPokemonRow() {
        assertNull(PokemonCatalogNotice.versionOf(emptyList()))
    }
}
