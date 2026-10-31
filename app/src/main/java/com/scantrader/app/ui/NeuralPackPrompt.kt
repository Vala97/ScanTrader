package com.scantrader.app.ui

import com.scantrader.app.vision.NeuralPackManager
import com.scantrader.app.vision.NeuralPackState
import com.scantrader.app.vision.PokemonCatalogIndex

/** One Pokémon catalog line on the last introduction page. */
data class NeuralPackCatalogLine(
    val name: String,
    val sizeBytes: Long = 0L,
    /** Null when the phone has not said yet whether the file is installed. */
    val installed: Boolean? = null,
)

/**
 * Copy for the Magic neural pack and the Pokémon catalogs.
 * The first-launch dialog is gone: this text is the last introduction page.
 * [shouldShow] stays for the old "offer once, hide while downloading or when
 * both are already installed" rule.
 */
object NeuralPackPrompt {
    const val TITLE = "Riconoscimento Magic e Pokémon"
    const val MAGIC_GROUP = "Magic"
    const val POKEMON_GROUP = "Pokémon"
    const val MAGIC_PACK = "Pack neurale Magic"
    const val TO_DOWNLOAD = "da scaricare"
    const val ALREADY_INSTALLED = "già installato"

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

    /**
     * Intro plus two groups. Magic is always listed. Pokémon catalogs keep the
     * old name and size, and add «da scaricare» or «già installato» when known.
     * [magicSizeLabel] is the pack size from the neural-pack source; blank omits it.
     */
    fun body(
        magicInstalled: Boolean,
        catalogs: List<NeuralPackCatalogLine>,
        magicSizeLabel: String? = NeuralPackManager.APPROX_SIZE_LABEL,
    ): String {
        val intro =
            "Per riconoscere meglio le carte puoi scaricare il pack neurale Magic e i cataloghi Pokémon. " +
                "Restano sul telefono e funzionano offline. Puoi scaricarli anche dopo da Impostazioni."
        val magic = bullet(
            MAGIC_PACK,
            magicSizeLabel?.trim()?.takeIf { it.isNotEmpty() },
            magicInstalled,
        )
        val groups = buildString {
            append(MAGIC_GROUP)
            append('\n')
            append(magic)
            if (catalogs.isNotEmpty()) {
                append("\n\n")
                append(POKEMON_GROUP)
                append('\n')
                append(catalogs.joinToString("\n") { catalog ->
                    val size = catalog.sizeBytes.takeIf { it > 0L }?.let(PokemonCatalogIndex::formatBytes)
                    bullet(catalog.name, size, catalog.installed)
                })
            }
        }
        return intro + "\n\n" + groups
    }

    private fun bullet(name: String, sizeLabel: String?, installed: Boolean?): String {
        val sized = if (sizeLabel.isNullOrBlank()) name else "$name ($sizeLabel)"
        val state = when (installed) {
            true -> ALREADY_INSTALLED
            false -> TO_DOWNLOAD
            null -> null
        }
        return if (state == null) "• $sized" else "• $sized, $state"
    }
}
