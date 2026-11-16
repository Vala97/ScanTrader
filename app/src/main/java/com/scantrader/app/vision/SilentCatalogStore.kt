package com.scantrader.app.vision

import com.google.gson.GsonBuilder
import java.io.File

/** What a verified download is waiting to become, on the next scanner open. */
data class StagedCatalog(
    val version: String,
    val sha256: String,
    val size: Long,
    val latestSetName: String? = null,
    val updatedAt: String? = null,
)

data class CatalogStatusLines(
    val magic: String? = null,
    val pokemon: String? = null,
)

/**
 * Live catalogs stay put while a download is checked. [activate] is the only
 * rename, and the scanner calls it before recognition starts.
 */
class SilentCatalogStore(private val visionDir: File) {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun magicInstalled(): Boolean = magicLive().isFile

    fun magicVersion(): String? = readText(File(visionDir, "mtg.version"))

    fun pokemonInstalled(): Boolean = pokemonLive().isFile

    fun pokemonVersion(): String? = readPokemonMeta()?.version?.takeIf { it.isNotBlank() }

    fun lines(): CatalogStatusLines {
        val magicDisplay = readJson(File(visionDir, "mtg.display.json"), StagedCatalog::class.java)
        val magicWhen = magicDisplay?.updatedAt ?: magicVersion()
        val pokemonMeta = readPokemonMeta()
        val pokemonWhen = pokemonMeta?.updatedAt ?: pokemonMeta?.version
        return CatalogStatusLines(
            magic = CatalogStatusText.line("Magic", magicWhen, magicDisplay?.latestSetName),
            pokemon = CatalogStatusText.line("Pokémon", pokemonWhen, pokemonMeta?.latestSetName),
        )
    }

    /** Move a verified file into the pending slot. Does not touch the live catalog. */
    fun stage(id: String, verified: File, info: StagedCatalog) {
        val dest = pendingFile(id)
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        if (!verified.renameTo(dest)) {
            verified.copyTo(dest, overwrite = true)
            verified.delete()
        }
        metaFile(id).writeText(gson.toJson(info), Charsets.UTF_8)
    }

    /**
     * Swap every pending catalog into place. A failed swap leaves that live file
     * and its pending pair as they were.
     */
    fun activate(): Set<String> {
        val applied = linkedSetOf<String>()
        if (promote("mtg", magicLive())) applied += "mtg"
        if (promote(POKEMON_ID, pokemonLive())) applied += POKEMON_ID
        return applied
    }

    private fun promote(id: String, live: File): Boolean {
        val staged = pendingFile(id)
        val meta = metaFile(id)
        if (!staged.isFile || !meta.isFile) return false
        val info = readJson(meta, StagedCatalog::class.java) ?: return false
        if (!CatalogFiles.swap(live, staged)) return false
        try {
            if (id == "mtg") {
                File(visionDir, "mtg.version").writeText(info.version)
                File(visionDir, "mtg.display.json").writeText(gson.toJson(info), Charsets.UTF_8)
            } else {
                val stored = InstalledStcat(
                    id = id,
                    name = "Pokémon (inglese)",
                    version = info.version,
                    sha256 = info.sha256,
                    size = live.length(),
                    latestSetName = info.latestSetName,
                    updatedAt = info.updatedAt,
                )
                pokemonMeta().writeText(gson.toJson(stored), Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            meta.delete()
            throw t
        }
        meta.delete()
        return true
    }

    private fun magicLive(): File = File(visionDir, "mtg.stcat")

    private fun pokemonLive(): File = File(visionDir, "catalogs/$POKEMON_ID.stcat")

    private fun pokemonMeta(): File = File(visionDir, "catalogs/$POKEMON_ID.meta.json")

    private fun pendingFile(id: String): File = File(visionDir, "pending/$id.stcat")

    private fun metaFile(id: String): File = File(visionDir, "pending/$id.json")

    private fun readPokemonMeta(): InstalledStcat? =
        readJson(pokemonMeta(), InstalledStcat::class.java)

    private fun <T> readJson(file: File, type: Class<T>): T? {
        if (!file.isFile) return null
        return runCatching { gson.fromJson(file.readText(Charsets.UTF_8), type) }.getOrNull()
    }

    private fun readText(file: File): String? =
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val POKEMON_ID = "pokemon-en"
    }
}
