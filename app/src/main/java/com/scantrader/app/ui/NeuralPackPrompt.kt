package com.scantrader.app.ui

import com.scantrader.app.vision.NeuralPackState
import com.scantrader.app.vision.PokemonCatalogIndex

/**
 * First-launch suggestion for the Magic neural pack and the Pokémon catalogs.
 * Shown once. Hidden while a download is already running, and hidden when
 * Magic is on the device and a Pokémon catalog is already installed.
 */
object NeuralPackPrompt {
    const val TITLE = "Riconoscimento Magic e Pokémon"

    fun shouldShow(
        alreadyShown: Boolean,
        magic: NeuralPackState,
        pokemonCatalogInstalled: Boolean = false,
    ): Boolean {
        if (alreadyShown) return false
        return when (magic) {
            NeuralPackState.Loading,
            is NeuralPackState.Downloading,
            -> false
            is NeuralPackState.Ready,
            NeuralPackState.InstalledNotLoaded,
            -> !pokemonCatalogInstalled
            NeuralPackState.NotInstalled,
            is NeuralPackState.Error,
            -> true
        }
    }

    fun body(catalogs: List<Pair<String, Long>>): String {
        val intro =
            "Per riconoscere meglio le carte puoi scaricare il pack neurale Magic e i cataloghi Pokémon. " +
                "Restano sul telefono e funzionano offline. Puoi scaricarli anche dopo da Impostazioni."
        if (catalogs.isEmpty()) return intro
        val lines = catalogs.joinToString("\n") { (name, size) ->
            val label = if (size > 0L) "$name (${PokemonCatalogIndex.formatBytes(size)})" else name
            "• $label"
        }
        return intro + "\n\n" + lines
    }
}
