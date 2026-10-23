package com.scantrader.app.vision

import android.content.Context
import android.net.Uri
import com.scantrader.app.data.model.Game
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads and caches Milo ONNX + per-game embedding catalogs.
 * Model/catalogs: Hugging Face HanClinto/milo — AGPL-3.0 (MTG NPZ);
 * Pokémon `.stcat` is built offline via tools/build_pokemon_stcat.
 */
class NeuralPackManager(
    private val context: Context,
    baseHttp: OkHttpClient,
) {
    private val http: OkHttpClient = baseHttp.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val root = File(context.filesDir, "vision").apply { mkdirs() }
    private val modelFile = File(root, "milo.onnx")
    private val mtgCatalogFile = File(root, "mtg.stcat")
    private val pokemonCatalogFile = File(root, "pokemon.stcat")
    private val mutex = Mutex()

    private val _mtgState = MutableStateFlow(inspectGame(Game.MTG))
    val mtgState: StateFlow<NeuralPackState> = _mtgState.asStateFlow()

    private val _pokemonState = MutableStateFlow(inspectGame(Game.POKEMON))
    val pokemonState: StateFlow<NeuralPackState> = _pokemonState.asStateFlow()

    /** Back-compat alias used by Settings MTG section. */
    val state: StateFlow<NeuralPackState> get() = mtgState

    @Volatile
    private var embedder: MiloEmbedder? = null

    @Volatile
    private var mtgCatalog: EmbeddingCatalog? = null

    @Volatile
    private var pokemonCatalog: EmbeddingCatalog? = null

    fun isReady(game: Game): Boolean = when (game) {
        Game.MTG -> _mtgState.value is NeuralPackState.Ready
        Game.POKEMON -> _pokemonState.value is NeuralPackState.Ready
    }

    fun isReady(): Boolean = isReady(Game.MTG)

    fun isInstalled(game: Game): Boolean = when (game) {
        Game.MTG -> modelFile.exists() && mtgCatalogFile.exists()
        Game.POKEMON -> modelFile.exists() && pokemonCatalogFile.exists()
    }

    fun isInstalled(): Boolean = isInstalled(Game.MTG)

    fun embedderOrNull(): MiloEmbedder? = embedder

    fun catalogOrNull(game: Game): EmbeddingCatalog? = when (game) {
        Game.MTG -> mtgCatalog
        Game.POKEMON -> pokemonCatalog
    }

    fun catalogOrNull(): EmbeddingCatalog? = mtgCatalog

    suspend fun ensureLoaded(game: Game? = null): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val targets = when (game) {
                Game.MTG -> listOf(Game.MTG)
                Game.POKEMON -> listOf(Game.POKEMON)
                null -> listOf(Game.MTG, Game.POKEMON)
            }
            var any = false
            for (g in targets) {
                if (loadGameLocked(g)) any = true
            }
            any
        }
    }

    private fun loadGameLocked(game: Game): Boolean {
        val catalogFile = catalogFile(game)
        val stateFlow = stateFlow(game)
        if (!modelFile.exists() || !catalogFile.exists()) {
            stateFlow.value = NeuralPackState.NotInstalled
            return false
        }
        val existing = catalogOrNull(game)
        if (embedder != null && existing != null) {
            stateFlow.value = NeuralPackState.Ready(existing.size)
            return true
        }
        return runCatching {
            stateFlow.value = NeuralPackState.Loading
            com.scantrader.app.scan.ScanDebug.d(
                "neural: loading model=${modelFile.name} catalog=${catalogFile.name} game=$game",
            )
            if (embedder == null) {
                embedder = MiloEmbedder(modelFile)
            }
            val loaded = EmbeddingCatalog.load(catalogFile)
            setCatalog(game, loaded)
            stateFlow.value = NeuralPackState.Ready(loaded.size)
            com.scantrader.app.scan.ScanDebug.d("neural: ready game=$game cards=${loaded.size}")
            true
        }.getOrElse { err ->
            com.scantrader.app.scan.ScanDebug.e("neural: load failed game=$game", err)
            if (mtgCatalog == null && pokemonCatalog == null) {
                embedder?.close()
                embedder = null
            }
            setCatalog(game, null)
            stateFlow.value = NeuralPackState.Error(err.message ?: "Caricamento fallito")
            false
        }
    }

    /** Download Milo + Scryfall MTG NPZ → mtg.stcat. */
    suspend fun downloadAndInstall(
        onProgress: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                _mtgState.value = NeuralPackState.Downloading(0f, "Download modello…")
                onProgress("Download modello Milo…")
                downloadTo(MODEL_URL, modelFile) { p ->
                    _mtgState.value = NeuralPackState.Downloading(p * 0.15f, "Download modello…")
                }

                val npz = File(root, "mtg-download.npz")
                _mtgState.value = NeuralPackState.Downloading(0.15f, "Download catalogo MTG…")
                onProgress("Download catalogo MTG (~55 MB)…")
                downloadTo(CATALOG_NPZ_URL, npz) { p ->
                    _mtgState.value = NeuralPackState.Downloading(0.15f + p * 0.70f, "Download catalogo…")
                }

                _mtgState.value = NeuralPackState.Downloading(0.90f, "Conversione catalogo…")
                onProgress("Conversione catalogo…")
                val converted = EmbeddingCatalog.fromNpz(npz)
                converted.save(mtgCatalogFile)
                npz.delete()

                if (embedder == null) {
                    embedder = MiloEmbedder(modelFile)
                }
                mtgCatalog = converted
                _mtgState.value = NeuralPackState.Ready(converted.size)
                // Refresh Pokémon status if its catalog was already present.
                if (pokemonCatalogFile.exists() && pokemonCatalog == null) {
                    _pokemonState.value = NeuralPackState.InstalledNotLoaded
                }
                onProgress("Pack neurale MTG pronto (${converted.size} carte)")
                true
            } catch (t: Throwable) {
                if (mtgCatalog == null && pokemonCatalog == null) {
                    embedder?.close()
                    embedder = null
                }
                mtgCatalog = null
                _mtgState.value = NeuralPackState.Error(t.message ?: "Download fallito")
                false
            }
        }
    }

    /**
     * Import a pre-built `pokemon.stcat`. Downloads Milo ONNX if missing.
     */
    suspend fun importPokemonCatalog(
        uri: Uri,
        onProgress: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                _pokemonState.value = NeuralPackState.Downloading(0f, "Import catalogo…")
                onProgress("Import catalogo Pokémon…")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val tmp = File(root, "pokemon-import.stcat.part")
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    // Validate before replacing.
                    val loaded = EmbeddingCatalog.load(tmp)
                    if (pokemonCatalogFile.exists()) pokemonCatalogFile.delete()
                    if (!tmp.renameTo(pokemonCatalogFile)) {
                        tmp.copyTo(pokemonCatalogFile, overwrite = true)
                        tmp.delete()
                    }
                    if (!modelFile.exists()) {
                        _pokemonState.value = NeuralPackState.Downloading(0.3f, "Download modello…")
                        onProgress("Download modello Milo…")
                        downloadTo(MODEL_URL, modelFile) { p ->
                            _pokemonState.value =
                                NeuralPackState.Downloading(0.3f + p * 0.6f, "Download modello…")
                        }
                    }
                    if (embedder == null) {
                        embedder = MiloEmbedder(modelFile)
                    }
                    pokemonCatalog = loaded
                    _pokemonState.value = NeuralPackState.Ready(loaded.size)
                    onProgress("Pack Pokémon pronto (${loaded.size} carte)")
                    true
                } ?: run {
                    _pokemonState.value = NeuralPackState.Error("Impossibile leggere il file")
                    false
                }
            } catch (t: Throwable) {
                com.scantrader.app.scan.ScanDebug.e("neural: pokemon import failed", t)
                pokemonCatalog = null
                _pokemonState.value = NeuralPackState.Error(t.message ?: "Import fallito")
                false
            }
        }
    }

    /** Copy a local file path (e.g. after adb push) into the Pokémon catalog slot. */
    suspend fun importPokemonCatalogFile(file: File): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val loaded = EmbeddingCatalog.load(file)
                loaded.save(pokemonCatalogFile)
                if (!modelFile.exists()) {
                    downloadTo(MODEL_URL, modelFile) {}
                }
                if (embedder == null) {
                    embedder = MiloEmbedder(modelFile)
                }
                pokemonCatalog = loaded
                _pokemonState.value = NeuralPackState.Ready(loaded.size)
                true
            } catch (t: Throwable) {
                _pokemonState.value = NeuralPackState.Error(t.message ?: "Import fallito")
                false
            }
        }
    }

    fun delete(game: Game = Game.MTG) {
        when (game) {
            Game.MTG -> {
                mtgCatalog = null
                mtgCatalogFile.delete()
                _mtgState.value = NeuralPackState.NotInstalled
            }
            Game.POKEMON -> {
                pokemonCatalog = null
                pokemonCatalogFile.delete()
                _pokemonState.value = NeuralPackState.NotInstalled
            }
        }
        if (!mtgCatalogFile.exists() && !pokemonCatalogFile.exists()) {
            embedder?.close()
            embedder = null
            modelFile.delete()
        }
    }

    fun delete() = delete(Game.MTG)

    private fun catalogFile(game: Game): File = when (game) {
        Game.MTG -> mtgCatalogFile
        Game.POKEMON -> pokemonCatalogFile
    }

    private fun stateFlow(game: Game): MutableStateFlow<NeuralPackState> = when (game) {
        Game.MTG -> _mtgState
        Game.POKEMON -> _pokemonState
    }

    private fun setCatalog(game: Game, catalog: EmbeddingCatalog?) {
        when (game) {
            Game.MTG -> mtgCatalog = catalog
            Game.POKEMON -> pokemonCatalog = catalog
        }
    }

    private fun inspectGame(game: Game): NeuralPackState {
        val cat = catalogFile(game)
        return if (modelFile.exists() && cat.exists()) NeuralPackState.InstalledNotLoaded
        else if (cat.exists() && !modelFile.exists()) NeuralPackState.InstalledNotLoaded
        else NeuralPackState.NotInstalled
    }

    private fun downloadTo(url: String, dest: File, onFraction: (Float) -> Unit) {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            val body = response.body ?: error("Empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            val tmp = File(dest.absolutePath + ".part")
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var readTotal = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        readTotal += n
                        if (total > 0) onFraction((readTotal.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }
    }

    companion object {
        private const val HF = "https://huggingface.co/HanClinto/milo/resolve/main"
        const val MODEL_URL = "$HF/model.onnx"
        const val CATALOG_NPZ_URL =
            "$HF/catalogs/milo1-scryfall-mtg-2026-07-09.npz"
        const val APPROX_SIZE_LABEL = "~60 MB"
        const val POKEMON_PACK_HINT =
            "Importa un .stcat generato con tools/build_pokemon_stcat (AGPL Milo)."
    }
}

sealed class NeuralPackState {
    data object NotInstalled : NeuralPackState()
    data object InstalledNotLoaded : NeuralPackState()
    data object Loading : NeuralPackState()
    data class Downloading(val progress: Float, val label: String) : NeuralPackState()
    data class Ready(val cardCount: Int) : NeuralPackState()
    data class Error(val message: String) : NeuralPackState()
}
