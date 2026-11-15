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
            StcatCatalogEntry("mtg", "Magic", "2026.11.13", 1L, "aa", "https://example/mtg"),
            StcatCatalogEntry("pokemon-en", "Pokémon", "2026.10.26", 2L, "bb", "https://example/p"),
        )
        assertEquals("2026.10.26", PokemonCatalogNotice.versionOf(entries))
    }

    @Test
    fun ignoresAMissingPokemonRow() {
        assertNull(PokemonCatalogNotice.versionOf(emptyList()))
    }
}
