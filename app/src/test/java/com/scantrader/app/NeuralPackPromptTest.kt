package com.scantrader.app

import com.scantrader.app.ui.NeuralPackPrompt
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
    fun promptListsPokemonCatalogsWhenTheIndexIsKnown() {
        val listed = NeuralPackPrompt.body(listOf("Pokémon (inglese)" to 13_000_000L))
        assertTrue(listed.contains("pack neurale Magic"))
        assertTrue(listed.contains("cataloghi Pokémon"))
        assertTrue(listed.contains("Pokémon (inglese)"))
        assertTrue(listed.contains("12,4"))
        val generic = NeuralPackPrompt.body(emptyList())
        assertTrue(generic.contains("cataloghi Pokémon"))
        assertFalse(generic.contains("•"))
    }
}
