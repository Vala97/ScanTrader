package com.scantrader.app.vision

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a newer Magic or Pokémon catalog into a pending file.
 * The live file changes only in [activatePending], which the scanner calls
 * when a visit starts. Nothing here posts a notification.
 */
class SilentCatalogUpdates(
    private val visionDir: File,
    private val http: OkHttpClient,
    private val forgetLoaded: (magic: Boolean, pokemon: Boolean) -> Unit,
) {
    private val store = SilentCatalogStore(visionDir)
    private val scannerInUse = AtomicBoolean(false)

    fun setScannerInUse(inUse: Boolean) {
        scannerInUse.set(inUse)
    }

    fun statusLines(): CatalogStatusLines = store.lines()

    /** Apply staged catalogs unless a scan visit is already running. */
    fun activatePending() {
        if (scannerInUse.get()) return
        val applied = store.activate()
        if (applied.isEmpty()) return
        forgetLoaded("mtg" in applied, SilentCatalogStore.POKEMON_ID in applied)
    }

    /** True when every attempt finished or there was nothing to do. False retries later. */
    suspend fun stageAll(): Boolean = withContext(Dispatchers.IO) {
        val entries = fetchIndex() ?: return@withContext false
        var ok = true
        val magic = PokemonCatalogIndex.mtgEntry(entries)
        if (magic != null && CatalogUpdateRules.shouldDownload(
                store.magicInstalled(),
                store.magicVersion(),
                magic.version,
            )
        ) {
            ok = stageMagic(magic) && ok
        }
        val pokemon = entries.firstOrNull { it.id == SilentCatalogStore.POKEMON_ID }
        if (pokemon != null && CatalogUpdateRules.shouldDownload(
                store.pokemonInstalled(),
                store.pokemonVersion(),
                pokemon.version,
            )
        ) {
            ok = stageFile(pokemon, SilentCatalogStore.POKEMON_ID) && ok
        }
        ok
    }

    private fun stageMagic(entry: StcatCatalogEntry): Boolean {
        val npz = File(visionDir, "pending/mtg-download.npz")
        npz.parentFile?.mkdirs()
        if (!downloadVerified(entry, npz)) return false
        val converted = File(visionDir, "pending/mtg-converted.stcat")
        return try {
            EmbeddingCatalog.fromNpz(npz).save(converted)
            store.stage("mtg", converted, stagedInfo(entry))
            npz.delete()
            true
        } catch (t: Throwable) {
            npz.delete()
            converted.delete()
            com.scantrader.app.scan.ScanDebug.w("catalog: magic stage failed ${t.javaClass.simpleName}")
            false
        }
    }

    private fun stageFile(entry: StcatCatalogEntry, id: String): Boolean {
        val part = File(visionDir, "pending/$id.part")
        part.parentFile?.mkdirs()
        if (!downloadVerified(entry, part)) return false
        return try {
            EmbeddingCatalog.load(part)
            store.stage(id, part, stagedInfo(entry))
            true
        } catch (t: Throwable) {
            part.delete()
            com.scantrader.app.scan.ScanDebug.w("catalog: $id stage failed ${t.javaClass.simpleName}")
            false
        }
    }

    private fun downloadVerified(entry: StcatCatalogEntry, dest: File): Boolean {
        if (dest.exists()) dest.delete()
        return try {
            val request = Request.Builder()
                .url(entry.url)
                .header("User-Agent", "ScanTrader")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                dest.parentFile?.mkdirs()
                dest.outputStream().use { output ->
                    response.body?.byteStream()?.use { input -> input.copyTo(output) }
                        ?: error("empty body")
                }
            }
            val size = dest.length()
            val hash = CatalogFiles.sha256(dest)
            if (!CatalogUpdateRules.payloadMatches(hash, entry.sha256, size, entry.size)) {
                dest.delete()
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            dest.delete()
            com.scantrader.app.scan.ScanDebug.w("catalog: download failed ${t.javaClass.simpleName}")
            false
        }
    }

    private fun fetchIndex(): List<StcatCatalogEntry>? {
        return try {
            val request = Request.Builder()
                .url(PokemonCatalogIndex.INDEX_URL)
                .header("Accept", "application/json")
                .header("User-Agent", "ScanTrader")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string() ?: error("empty index")
                PokemonCatalogIndex.parse(body)
            }
        } catch (t: Throwable) {
            com.scantrader.app.scan.ScanDebug.w("catalog: index failed ${t.javaClass.simpleName}")
            null
        }
    }

    private fun stagedInfo(entry: StcatCatalogEntry) = StagedCatalog(
        version = entry.version,
        sha256 = entry.sha256,
        size = entry.size,
        latestSetName = entry.latestSetName,
        updatedAt = entry.updatedAt ?: entry.latestSetDate,
    )

    companion object {
        const val WORK_NAME = "silent-catalog-update"
        val INTERVAL_HOURS = 12L
        val INTERVAL_UNIT = TimeUnit.HOURS
    }
}
