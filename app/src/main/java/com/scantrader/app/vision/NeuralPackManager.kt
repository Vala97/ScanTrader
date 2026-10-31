package com.scantrader.app.vision

import android.content.Context
import android.net.Uri
import com.scantrader.app.data.model.Game
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
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
 * Model/catalogs: Hugging Face HanClinto/milo — AGPL-3.0 (MTG NPZ).
 * Pokémon `.stcat` files are imported by hand (`pokemon.stcat`) or downloaded
 * into `vision/catalogs/` and combined for the gallery.
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
    private val pokemonCountFile = File(root, "pokemon.count")
    private val mutex = Mutex()

    private val _mtgState = MutableStateFlow(inspectGame(Game.MTG))
    val mtgState: StateFlow<NeuralPackState> = _mtgState.asStateFlow()

    private val _pokemonState = MutableStateFlow(inspectGame(Game.POKEMON))
    private val _riftboundState = MutableStateFlow<NeuralPackState>(NeuralPackState.NotInstalled)
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
        Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA -> embedder != null && ctCatalogs[game] != null
        Game.RIFTBOUND, Game.OTHER -> false // nessun pack visivo per Riftbound (per ora)
    }

    fun isReady(): Boolean = isReady(Game.MTG)

    fun isInstalled(game: Game): Boolean = when (game) {
        Game.MTG -> modelFile.exists() && mtgCatalogFile.exists()
        Game.POKEMON -> modelFile.exists() && pokemonCatalogFiles().isNotEmpty()
        Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA -> modelFile.exists() && ctFile(game).exists()
        Game.RIFTBOUND, Game.OTHER -> false
    }

    fun isInstalled(): Boolean = isInstalled(Game.MTG)

    fun embedderOrNull(): MiloEmbedder? = embedder

    fun catalogOrNull(game: Game): EmbeddingCatalog? = when (game) {
        Game.MTG -> mtgCatalog
        Game.POKEMON -> pokemonCatalog
        Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA -> ctCatalogs[game]
        Game.RIFTBOUND, Game.OTHER -> null
    }

    fun catalogOrNull(): EmbeddingCatalog? = mtgCatalog

    suspend fun ensureLoaded(game: Game? = null): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val targets = when (game) {
                Game.MTG -> listOf(Game.MTG)
                Game.POKEMON -> listOf(Game.POKEMON)
                Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA -> return@withLock loadCtLocked(game)
                Game.RIFTBOUND, Game.OTHER -> emptyList()
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
        if (game == Game.POKEMON) return loadPokemonLocked()
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

    /**
     * Manual `pokemon.stcat` plus every downloaded catalog. One corrupt file
     * is skipped so the others still load.
     */
    private fun loadPokemonLocked(): Boolean {
        val files = pokemonCatalogFiles()
        if (files.isEmpty()) {
            pokemonCatalog = null
            _pokemonState.value = NeuralPackState.NotInstalled
            return false
        }
        if (!modelFile.exists()) {
            _pokemonState.value = NeuralPackState.InstalledNotLoaded
            return false
        }
        val existing = pokemonCatalog
        if (embedder != null && existing != null) {
            _pokemonState.value = NeuralPackState.Ready(existing.size)
            return true
        }
        return runCatching {
            _pokemonState.value = NeuralPackState.Loading
            if (embedder == null) {
                embedder = MiloEmbedder(modelFile)
            }
            val loaded = files.mapNotNull { file ->
                runCatching { EmbeddingCatalog.load(file) }
                    .onFailure {
                        com.scantrader.app.scan.ScanDebug.e("neural: skip corrupt ${file.name}", it)
                    }
                    .getOrNull()
            }
            val combined = EmbeddingCatalog.combine(loaded)
                ?: error("Nessun catalogo Pokémon valido")
            pokemonCatalog = combined
            _pokemonState.value = NeuralPackState.Ready(combined.size)
            com.scantrader.app.scan.ScanDebug.d(
                "neural: ready game=POKEMON cards=${combined.size} files=${files.size}",
            )
            true
        }.getOrElse { err ->
            com.scantrader.app.scan.ScanDebug.e("neural: load failed game=POKEMON", err)
            if (mtgCatalog == null) {
                embedder?.close()
                embedder = null
            }
            pokemonCatalog = null
            _pokemonState.value = NeuralPackState.Error(err.message ?: "Caricamento fallito")
            false
        }
    }

    /** Download Milo if a Pokémon catalog is present, then reload the gallery. */
    suspend fun ensurePokemonReady(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!modelFile.exists() && pokemonCatalogFiles().isNotEmpty()) {
                    _pokemonState.value = NeuralPackState.Downloading(0f, "Download modello…")
                    downloadTo(MODEL_URL, modelFile, MODEL_SHA256) { }
                }
                pokemonCatalog = null
                loadPokemonLocked()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _pokemonState.value = NeuralPackState.Error(t.message ?: "Download modello fallito")
                false
            }
        }
    }

    suspend fun reloadPokemonCatalog(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            pokemonCatalog = null
            loadPokemonLocked()
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
                downloadTo(MODEL_URL, modelFile, MODEL_SHA256) { p ->
                    _mtgState.value = NeuralPackState.Downloading(p * 0.15f, "Download modello…")
                }

                val npz = File(root, "mtg-download.npz")
                _mtgState.value = NeuralPackState.Downloading(0.15f, "Download catalogo MTG…")
                onProgress("Download catalogo MTG (~55 MB)…")
                downloadTo(CATALOG_NPZ_URL, npz, CATALOG_NPZ_SHA256) { p ->
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
                if (pokemonCatalogFiles().isNotEmpty() && pokemonCatalog == null) {
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
                    rememberManualCount(loaded.size)
                    if (!modelFile.exists()) {
                        _pokemonState.value = NeuralPackState.Downloading(0.3f, "Download modello…")
                        onProgress("Download modello Milo…")
                        downloadTo(MODEL_URL, modelFile, MODEL_SHA256) { p ->
                            _pokemonState.value =
                                NeuralPackState.Downloading(0.3f + p * 0.6f, "Download modello…")
                        }
                    }
                    pokemonCatalog = null
                    if (!loadPokemonLocked()) {
                        _pokemonState.value = NeuralPackState.Error("Import fallito")
                        return@withLock false
                    }
                    onProgress("Pack Pokémon pronto (${pokemonCatalog?.size ?: loaded.size} carte)")
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
                rememberManualCount(loaded.size)
                if (!modelFile.exists()) {
                    downloadTo(MODEL_URL, modelFile, MODEL_SHA256) {}
                }
                pokemonCatalog = null
                loadPokemonLocked()
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
                pokemonCountFile.delete()
                _pokemonState.value = if (pokemonCatalogFiles().isEmpty()) {
                    NeuralPackState.NotInstalled
                } else {
                    NeuralPackState.InstalledNotLoaded
                }
            }
            Game.RIFTBOUND, Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA, Game.OTHER -> Unit
        }
        if (!mtgCatalogFile.exists() && pokemonCatalogFiles().isEmpty() && CT_GAMES.none { ctFile(it).exists() }) {
            embedder?.close()
            embedder = null
            modelFile.delete()
        }
    }

    fun delete() = delete(Game.MTG)

    fun hasManualPokemonCatalog(): Boolean = pokemonCatalogFile.exists()

    fun manualPokemonCardCount(): Int? {
        if (!pokemonCatalogFile.exists() || !pokemonCountFile.exists()) return null
        return pokemonCountFile.readText().trim().toIntOrNull()?.takeIf { it > 0 }
    }

    private fun rememberManualCount(count: Int) {
        if (count > 0) pokemonCountFile.writeText(count.toString())
    }

    private fun catalogFile(game: Game): File = when (game) {
        Game.MTG -> mtgCatalogFile
        Game.POKEMON -> pokemonCatalogFile
        Game.RIFTBOUND, Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA, Game.OTHER -> File(root, "riftbound-nessun-pack")
    }

    private fun stateFlow(game: Game): MutableStateFlow<NeuralPackState> = when (game) {
        Game.MTG -> _mtgState
        Game.POKEMON -> _pokemonState
        Game.RIFTBOUND, Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA, Game.OTHER -> _riftboundState
    }

    private fun setCatalog(game: Game, catalog: EmbeddingCatalog?) {
        when (game) {
            Game.MTG -> mtgCatalog = catalog
            Game.POKEMON -> pokemonCatalog = catalog
            Game.RIFTBOUND, Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA, Game.OTHER -> Unit
        }
    }

    private fun inspectGame(game: Game): NeuralPackState {
        val present = when (game) {
            Game.MTG -> mtgCatalogFile.exists()
            Game.POKEMON -> pokemonCatalogFiles().isNotEmpty()
            Game.RIFTBOUND, Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA, Game.OTHER -> false
        }
        return if (present) NeuralPackState.InstalledNotLoaded else NeuralPackState.NotInstalled
    }

    private fun pokemonCatalogFiles(): List<File> {
        val downloaded = File(root, "catalogs").listFiles { file ->
            file.isFile && file.name.endsWith(".stcat") && !file.name.endsWith(".part")
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val manual = if (pokemonCatalogFile.exists()) listOf(pokemonCatalogFile) else emptyList()
        return downloaded + manual
    }

    // ---- One Piece, Dragon Ball, Yu-Gi-Oh!, Union Arena: galleria costruita dalle immagini CardTrader ----
    private val ctCatalogs = java.util.concurrent.ConcurrentHashMap<Game, EmbeddingCatalog>()
    private fun ctFile(game: Game) = File(root, "ct-${game.csvLabel}.stcat")
    private val ctChecked = java.util.Collections.synchronizedSet(mutableSetOf<Game>())

    private fun loadCtLocked(game: Game): Boolean {
        ctCatalogs[game]?.let { if (embedder != null) return true }
        val file = ctFile(game)
        if (!modelFile.exists() || !file.exists()) return false
        return runCatching {
            if (embedder == null) embedder = MiloEmbedder(modelFile)
            ctCatalogs[game] = EmbeddingCatalog.load(file)
            com.scantrader.app.scan.ScanDebug.d("neural: ready game=$game cards=${ctCatalogs[game]?.size}")
            true
        }.getOrElse {
            com.scantrader.app.scan.ScanDebug.e("neural: load failed game=$game", it)
            false
        }
    }

    /**
     * Scarica (se manca o sul server e' cambiata) la galleria del gioco da `<baseUrl><gioco>.stcat`
     * e il modello Milo se non c'e'. Senza indirizzo o senza rete resta quella gia' sul telefono.
     */
    suspend fun ensureCtGallery(game: Game, baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (game !in CT_GAMES) return@withContext false
        mutex.withLock {
            val file = ctFile(game)
            val url = baseUrl.trimEnd('/') + "/" + game.csvLabel + ".stcat"
            // una volta per apertura dell'app: si riscarica se sul server c'e' una galleria diversa (altra misura)
            val stale = baseUrl.isNotBlank() && game !in ctChecked && runCatching {
                http.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                    r.isSuccessful && r.header("Content-Length")?.toLongOrNull()?.let { it != file.length() } != false
                }
            }.getOrDefault(false)
            ctChecked += game
            if (stale) {
                runCatching {
                    if (!modelFile.exists()) downloadTo(MODEL_URL, modelFile, MODEL_SHA256) {}
                    val tmp = File(root, file.name + ".download")
                    downloadTo(url, tmp) {}
                    EmbeddingCatalog.load(tmp) // valida prima di sostituire
                    if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
                    ctCatalogs.remove(game)
                    com.scantrader.app.scan.ScanDebug.d("neural: galleria $game scaricata")
                }.onFailure { com.scantrader.app.scan.ScanDebug.w("neural: galleria $game non scaricata", it) }
            }
            loadCtLocked(game)
        }
    }

    private fun downloadTo(
        url: String,
        dest: File,
        expectedSha256: String? = null,
        onFraction: (Float) -> Unit,
    ) {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            val body = response.body ?: error("Empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            val tmp = File(dest.absolutePath + ".part")
            try {
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
                if (expectedSha256 != null && !sha256(tmp).equals(expectedSha256, ignoreCase = true)) {
                    tmp.delete()
                    error(CHECKSUM_ERROR)
                }
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
            if (dest.exists() && !dest.delete()) {
                tmp.delete()
                error(CHECKSUM_ERROR)
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    companion object {
        private const val HF = "https://huggingface.co/HanClinto/milo/resolve/main"
        const val MODEL_URL = "$HF/model.onnx"
        const val CATALOG_NPZ_URL =
            "$HF/catalogs/milo1-scryfall-mtg-2026-07-09.npz"
        const val APPROX_SIZE_LABEL = "~60 MB"
        const val CHECKSUM_ERROR = "File danneggiato (checksum). Il pack non è stato installato."

        /** Hugging Face LFS oid for `model.onnx` on HanClinto/milo. */
        const val MODEL_SHA256 = "bd13d8d60383c69da04dce261f32e93fdaeaa8fd618fbc991e7385f71b3d45df"

        /** Hugging Face LFS oid for `catalogs/milo1-scryfall-mtg-2026-07-09.npz`. */
        const val CATALOG_NPZ_SHA256 = "c5cb0dc150eb9f5b183396dd25aecaa9efc5ce3d001dd8bef282fcc73ee778be"
        val CT_GAMES = setOf(Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA)
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
