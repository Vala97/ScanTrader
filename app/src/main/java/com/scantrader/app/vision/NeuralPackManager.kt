package com.scantrader.app.vision

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads and caches Milo ONNX + Scryfall MTG embedding catalog (~60 MB).
 * Models/catalogs: Hugging Face HanClinto/milo — AGPL-3.0.
 */
class NeuralPackManager(
    context: Context,
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
    private val catalogFile = File(root, "mtg.stcat")
    private val mutex = Mutex()

    private val _state = MutableStateFlow(inspectState())
    val state: StateFlow<NeuralPackState> = _state.asStateFlow()

    @Volatile
    private var embedder: MiloEmbedder? = null

    @Volatile
    private var catalog: EmbeddingCatalog? = null

    fun isReady(): Boolean = _state.value is NeuralPackState.Ready

    /** True when files are on disk (may still need [ensureLoaded]). */
    fun isInstalled(): Boolean =
        modelFile.exists() && catalogFile.exists()

    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (embedder != null && catalog != null) {
                _state.value = NeuralPackState.Ready(catalog!!.size)
                return@withLock true
            }
            if (!modelFile.exists() || !catalogFile.exists()) {
                _state.value = NeuralPackState.NotInstalled
                return@withLock false
            }
            runCatching {
                _state.value = NeuralPackState.Loading
                com.scantrader.app.scan.ScanDebug.d("neural: loading model=${modelFile.name} catalog=${catalogFile.name}")
                embedder = MiloEmbedder(modelFile)
                catalog = EmbeddingCatalog.load(catalogFile)
                _state.value = NeuralPackState.Ready(catalog!!.size)
                com.scantrader.app.scan.ScanDebug.d("neural: ready cards=${catalog!!.size}")
                true
            }.getOrElse { err ->
                com.scantrader.app.scan.ScanDebug.e("neural: load failed", err)
                embedder?.close()
                embedder = null
                catalog = null
                _state.value = NeuralPackState.Error(err.message ?: "Caricamento fallito")
                false
            }
        }
    }

    suspend fun downloadAndInstall(
        onProgress: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                _state.value = NeuralPackState.Downloading(0f, "Download modello…")
                onProgress("Download modello Milo…")
                downloadTo(MODEL_URL, modelFile) { p ->
                    _state.value = NeuralPackState.Downloading(p * 0.15f, "Download modello…")
                }

                val npz = File(root, "mtg-download.npz")
                _state.value = NeuralPackState.Downloading(0.15f, "Download catalogo MTG…")
                onProgress("Download catalogo MTG (~55 MB)…")
                downloadTo(CATALOG_NPZ_URL, npz) { p ->
                    _state.value = NeuralPackState.Downloading(0.15f + p * 0.70f, "Download catalogo…")
                }

                _state.value = NeuralPackState.Downloading(0.90f, "Conversione catalogo…")
                onProgress("Conversione catalogo…")
                val converted = EmbeddingCatalog.fromNpz(npz)
                converted.save(catalogFile)
                npz.delete()

                embedder?.close()
                embedder = MiloEmbedder(modelFile)
                catalog = converted
                _state.value = NeuralPackState.Ready(converted.size)
                onProgress("Pack neurale pronto (${converted.size} carte)")
                true
            } catch (t: Throwable) {
                embedder?.close()
                embedder = null
                catalog = null
                _state.value = NeuralPackState.Error(t.message ?: "Download fallito")
                false
            }
        }
    }

    fun embedderOrNull(): MiloEmbedder? = embedder
    fun catalogOrNull(): EmbeddingCatalog? = catalog

    fun delete() {
        embedder?.close()
        embedder = null
        catalog = null
        modelFile.delete()
        catalogFile.delete()
        _state.value = NeuralPackState.NotInstalled
    }

    private fun inspectState(): NeuralPackState =
        if (modelFile.exists() && catalogFile.exists()) NeuralPackState.InstalledNotLoaded
        else NeuralPackState.NotInstalled

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
        /** Approximate download size shown in UI. */
        const val APPROX_SIZE_LABEL = "~60 MB"
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
