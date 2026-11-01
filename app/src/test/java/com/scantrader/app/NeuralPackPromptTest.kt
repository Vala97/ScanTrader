package com.scantrader.app

import com.scantrader.app.ui.NeuralPackCatalogLine
import com.scantrader.app.ui.NeuralPackPrompt
import com.scantrader.app.vision.NeuralPackManager
import com.scantrader.app.vision.NeuralPackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralPackPromptTest {
    @Test
    fun suggestsMagicAndPokemonOnceWhileEitherIsMissing() {
        assertTrue(
            NeuralPackPrompt.shouldShow(
                false,
                NeuralPackState.NotInstalled,
                pokemonCatalogInstalled = false,
            ),
        )
        assertTrue(
            NeuralPackPrompt.shouldShow(
                false,
                NeuralPackState.NotInstalled,
                pokemonCatalogInstalled = true,
            ),
        )
        assertTrue(
            NeuralPackPrompt.shouldShow(
                false,
                NeuralPackState.Error("rete"),
                pokemonCatalogInstalled = true,
            ),
        )
        assertTrue(
            NeuralPackPrompt.shouldShow(
                false,
                NeuralPackState.Ready(10),
                pokemonCatalogInstalled = false,
            ),
        )
        assertFalse(
            NeuralPackPrompt.shouldShow(
                true,
                NeuralPackState.NotInstalled,
                pokemonCatalogInstalled = false,
            ),
        )
        assertFalse(
            NeuralPackPrompt.shouldShow(
                false,
                NeuralPackState.Ready(10),
                pokemonCatalogInstalled = true,
            ),
        )
        assertFalse(
            NeuralPackPrompt.shouldShow(
                false,
                NeuralPackState.InstalledNotLoaded,
                pokemonCatalogInstalled = true,
            ),
        )
        assertFalse(
            NeuralPackPrompt.shouldShow(
                false,
                NeuralPackState.Downloading(0.2f, "pack"),
                pokemonCatalogInstalled = false,
            ),
        )
        assertFalse(
            NeuralPackPrompt.shouldShow(
                false,
                NeuralPackState.Loading,
                pokemonCatalogInstalled = false,
            ),
        )
    }

    @Test
    fun promptSplitsMagicAndPokemonAndMarksEachRow() {
        val listed = NeuralPackPrompt.body(
            magicInstalled = false,
            catalogs = listOf(
                NeuralPackCatalogLine("Pokémon (inglese)", 13_000_000L, installed = false),
                NeuralPackCatalogLine("Pokémon (italiano)", 8_000_000L, installed = true),
            ),
        )
        assertTrue(listed.contains("pack neurale Magic"))
        assertTrue(listed.contains("cataloghi Pokémon"))
        assertTrue(listed.contains(NeuralPackPrompt.MILO_LICENSE_NOTE))
        assertTrue(listed.contains(NeuralPackPrompt.MILO_SOURCE_URL))
        assertTrue(listed.contains("Pokémon (inglese)"))
        assertTrue(listed.contains("12,4"))
        assertTrue(listed.contains("(${NeuralPackManager.APPROX_SIZE_LABEL})"))
        assertTrue(listed.contains("Pack neurale Magic (${NeuralPackManager.APPROX_SIZE_LABEL}), da scaricare"))
        assertTrue(listed.contains("Pokémon (inglese) (12,4 MB), da scaricare"))
        assertTrue(listed.contains("Pokémon (italiano)"))
        assertTrue(listed.contains("già installato"))
        val magicGroup = listed.indexOf("\nMagic\n")
        val pokemonGroup = listed.indexOf("\nPokémon\n")
        assertTrue(magicGroup >= 0 && pokemonGroup > magicGroup)
        assertTrue(listed.indexOf("Pack neurale Magic") < listed.indexOf("Pokémon (inglese)"))
        assertFalse(listed.contains("—"))
        assertFalse(listed.contains("–"))

        val installedMagic = NeuralPackPrompt.body(
            magicInstalled = true,
            catalogs = listOf(NeuralPackCatalogLine("Pokémon (inglese)", installed = null)),
        )
        assertTrue(installedMagic.contains("Pack neurale Magic (${NeuralPackManager.APPROX_SIZE_LABEL}), già installato"))
        assertTrue(installedMagic.contains("• Pokémon (inglese)"))
        assertFalse(installedMagic.contains("Pokémon (inglese),"))
        assertFalse(installedMagic.contains("Pokémon (inglese) ("))

        val noSize = NeuralPackPrompt.body(
            magicInstalled = false,
            catalogs = emptyList(),
            magicSizeLabel = "  ",
        )
        assertTrue(noSize.contains("• Pack neurale Magic, da scaricare"))
        assertFalse(noSize.contains("• Pack neurale Magic ("))
        assertTrue(noSize.contains("\nMagic\n"))
        assertFalse(noSize.contains("\nPokémon\n"))
    }
}
