package com.scantrader.app.vision

import com.google.gson.GsonBuilder
import java.io.File

/** Sidecar stored next to a downloaded `.stcat`. */
data class InstalledStcat(
    val id: String,
    val name: String,
    val version: String,
    val sha256: String,
    val size: Long,
    val cardCount: Int = 0,
)

/**
 * Downloaded Pokémon catalogs live in `files/vision/catalogs/<id>.stcat`.
 * The hand-imported file stays `files/vision/pokemon.stcat` and is not replaced.
 */
class PokemonCatalogLibrary(private val visionDir: File) {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val manualFile = File(visionDir, "pokemon.stcat")

    fun catalogsDir(): File = File(visionDir, "catalogs")

    fun catalogFile(id: String): File {
        val safe = PokemonCatalogIndex.fileIdOrNull(id) ?: error("Id catalogo non valido")
        return File(catalogsDir(), "$safe.stcat")
    }

    fun partFile(id: String): File = File(catalogFile(id).absolutePath + ".part")

    private fun metaFile(id: String): File {
        val safe = PokemonCatalogIndex.fileIdOrNull(id) ?: error("Id catalogo non valido")
        return File(catalogsDir(), "$safe.meta.json")
    }

    fun hasAny(): Boolean = manualFile.exists() || listInstalled().isNotEmpty()

    fun listInstalled(): List<InstalledStcat> {
        val dir = catalogsDir()
        val files = dir.listFiles { file ->
            file.isFile && file.name.endsWith(".stcat") && !file.name.endsWith(".part")
        } ?: return emptyList()
        return files.sortedBy { it.name.lowercase() }.map { file ->
            val id = file.name.removeSuffix(".stcat")
            readMeta(id) ?: InstalledStcat(
                id = id,
                name = id,
                version = "",
                sha256 = "",
                size = file.length(),
            )
        }
    }

    fun read(id: String): InstalledStcat? {
        val file = runCatching { catalogFile(id) }.getOrNull() ?: return null
        if (!file.exists()) return null
        return readMeta(id) ?: InstalledStcat(
            id = PokemonCatalogIndex.fileIdOrNull(id) ?: id,
            name = id,
            version = "",
            sha256 = "",
            size = file.length(),
        )
    }

    /**
     * Check sha256, require a readable `.stcat`, then replace the live file.
     * A bad checksum or a corrupt catalog deletes the temp file and leaves
     * the installed catalog as it was.
     */
    fun installVerified(entry: StcatCatalogEntry, part: File) {
        val hash = PokemonCatalogIndex.sha256(part)
        if (!hash.equals(entry.sha256, ignoreCase = true)) {
            part.delete()
            error("File danneggiato (checksum)")
        }
        val loaded = try {
            EmbeddingCatalog.load(part)
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        val dest = catalogFile(entry.id)
        val meta = metaFile(entry.id)
        dest.parentFile?.mkdirs()
        val backup = File(dest.parentFile, dest.name + ".bak")
        val metaBackup = File(meta.parentFile, meta.name + ".bak")
        if (backup.exists()) backup.delete()
        val hadDest = dest.exists()
        if (hadDest && !dest.renameTo(backup)) {
            part.delete()
            error("Impossibile aggiornare il catalogo")
        }
        val moved = part.renameTo(dest) || copyIntoPlace(part, dest)
        if (!moved) {
            if (hadDest) backup.renameTo(dest)
            error("Impossibile installare il catalogo")
        }
        if (metaBackup.exists()) metaBackup.delete()
        val hadMeta = meta.exists()
        if (hadMeta) meta.renameTo(metaBackup)
        try {
            val stored = InstalledStcat(
                id = PokemonCatalogIndex.fileIdOrNull(entry.id) ?: entry.id,
                name = entry.name,
                version = entry.version,
                sha256 = hash.lowercase(),
                size = dest.length(),
                cardCount = loaded.size,
            )
            meta.writeText(gson.toJson(stored), Charsets.UTF_8)
        } catch (t: Throwable) {
            dest.delete()
            if (hadDest) backup.renameTo(dest)
            if (hadMeta) metaBackup.renameTo(meta)
            throw t
        }
        backup.delete()
        metaBackup.delete()
    }

    fun delete(id: String) {
        runCatching { catalogFile(id) }.getOrNull()?.delete()
        runCatching { metaFile(id) }.getOrNull()?.delete()
        runCatching { partFile(id) }.getOrNull()?.delete()
    }

    private fun readMeta(id: String): InstalledStcat? {
        val file = runCatching { metaFile(id) }.getOrNull() ?: return null
        if (!file.exists()) return null
        val parsed = runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), MetaDto::class.java)
        }.getOrNull() ?: return null
        val storedId = parsed.id?.trim().orEmpty()
        if (storedId.isEmpty()) return null
        return InstalledStcat(
            id = storedId,
            name = parsed.name?.trim().orEmpty().ifBlank { storedId },
            version = parsed.version?.trim().orEmpty(),
            sha256 = parsed.sha256?.trim().orEmpty(),
            size = parsed.size ?: file.length(),
            cardCount = parsed.cardCount ?: 0,
        )
    }

    private data class MetaDto(
        val id: String? = null,
        val name: String? = null,
        val version: String? = null,
        val sha256: String? = null,
        val size: Long? = null,
        val cardCount: Int? = null,
    )

    private fun copyIntoPlace(part: File, dest: File): Boolean {
        part.copyTo(dest, overwrite = true)
        part.delete()
        return dest.exists() && dest.length() > 0L
    }
}
