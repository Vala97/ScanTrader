package com.scantrader.app.vision

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

data class PokemonCatalogRow(
    val id: String,
    val name: String,
    val remoteVersion: String?,
    val remoteSize: Long?,
    val installedVersion: String?,
    val installedSize: Long?,
    val installed: Boolean,
    val updateAvailable: Boolean,
    val downloading: Boolean,
    val progress: Float,
    val error: String?,
    val cardCount: Int = 0,
) {
    /** Same shape as the Magic pack line: «Pronto · N carte» or «Non installato», then version and size. */
    fun statusLine(): String {
        if (downloading) return "Download… (${(progress * 100).toInt()}%)"
        val state = when {
            installed && cardCount > 0 -> "Pronto · $cardCount carte"
            installed -> "Pronto"
            else -> "Non installato"
        }
        val version = when {
            updateAvailable && !installedVersion.isNullOrBlank() && !remoteVersion.isNullOrBlank() ->
                "versione $installedVersion → $remoteVersion"
            installed && !installedVersion.isNullOrBlank() -> "versione $installedVersion"
            !remoteVersion.isNullOrBlank() -> "versione $remoteVersion"
            else -> null
        }
        val bytes = when {
            updateAvailable -> remoteSize ?: installedSize
            installed -> installedSize ?: remoteSize
            else -> remoteSize ?: installedSize
        }
        val size = bytes?.takeIf { it > 0L }?.let { PokemonCatalogIndex.formatBytes(it) }
        return listOfNotNull(state, version, size).joinToString(" · ")
    }
}

data class PokemonCatalogUi(
    val rows: List<PokemonCatalogRow> = emptyList(),
    val checking: Boolean = false,
    val message: String? = null,
    val anyInstalled: Boolean = false,
    /** Names and sizes from the last index, for the first-launch dialog. */
    val promptCatalogs: List<Pair<String, Long>> = emptyList(),
)

/**
 * Downloads Pokémon `.stcat` catalogs on [scope] (the process scope, so leaving
 * Settings does not cancel). The neural-pack mutex is taken only by [onChanged]
 * after the file is in place.
 */
class PokemonCatalogController(
    visionDir: File,
    baseHttp: OkHttpClient,
    private val scope: CoroutineScope,
    private val onChanged: suspend () -> Unit,
    private val indexUrl: String = PokemonCatalogIndex.INDEX_URL,
) {
    private val library = PokemonCatalogLibrary(visionDir)
    private val http: OkHttpClient = baseHttp.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val gate = Any()
    private var remote: List<StcatCatalogEntry> = emptyList()
    private val progress = mutableMapOf<String, Float>()
    private val errors = mutableMapOf<String, String>()
    private val jobs = mutableMapOf<String, Job>()
    private var checking = false
    private var message: String? = null

    private val _ui = MutableStateFlow(PokemonCatalogUi())
    val ui: StateFlow<PokemonCatalogUi> = _ui.asStateFlow()

    init {
        publish()
    }

    fun hasLocalCatalog(): Boolean = library.hasAny()

    fun refresh() {
        synchronized(gate) {
            if (checking) return
            checking = true
        }
        publish()
        scope.launch {
            fetchIntoMemory()
        }
    }

    /** Every catalog that is missing or older than the index. */
    fun downloadMissing() {
        scope.launch {
            if (!fetchIntoMemory()) return@launch
            val entries = synchronized(gate) { remote.toList() }
            for (entry in entries) {
                val local = library.read(entry.id)
                val needs = local == null ||
                    PokemonCatalogIndex.isUpdate(entry.version, local.version, true)
                if (needs) startDownload(entry)
            }
        }
    }

    fun download(id: String) {
        scope.launch {
            val known = synchronized(gate) { remote.firstOrNull { it.id == id } }
            val entry = known ?: run {
                if (!fetchIntoMemory()) return@launch
                synchronized(gate) { remote.firstOrNull { it.id == id } }
            }
            if (entry == null) {
                setError(id, "Catalogo non trovato nell’elenco.")
                return@launch
            }
            startDownload(entry)
        }
    }

    fun delete(id: String) {
        scope.launch {
            synchronized(gate) { jobs.remove(id)?.cancel() }
            library.delete(id)
            synchronized(gate) {
                progress.remove(id)
                errors.remove(id)
            }
            publish()
            runCatching { onChanged() }
        }
    }

    private suspend fun fetchIntoMemory(): Boolean {
        return try {
            val list = fetchIndex()
            synchronized(gate) {
                remote = list
                checking = false
                message = if (list.isEmpty()) "Nessun catalogo pubblicato." else null
            }
            publish()
            true
        } catch (cancelled: CancellationException) {
            synchronized(gate) { checking = false }
            publish()
            throw cancelled
        } catch (_: Throwable) {
            synchronized(gate) {
                checking = false
                message = OFFLINE
            }
            publish()
            false
        }
    }

    private fun startDownload(entry: StcatCatalogEntry) {
        synchronized(gate) {
            if (jobs[entry.id]?.isActive == true) return
            jobs[entry.id] = scope.launch { runDownload(entry) }
        }
    }

    private suspend fun runDownload(entry: StcatCatalogEntry) {
        val id = entry.id
        val part = library.partFile(id)
        try {
            setProgress(id, 0f)
            part.parentFile?.mkdirs()
            if (part.exists()) part.delete()
            downloadTo(entry.url, part, entry.size) { fraction -> setProgress(id, fraction) }
            setProgress(id, 1f)
            library.installVerified(entry, part)
            synchronized(gate) { errors.remove(id) }
            publish()
            onChanged()
        } catch (cancelled: CancellationException) {
            part.delete()
            throw cancelled
        } catch (t: Throwable) {
            part.delete()
            setError(id, messageFor(t))
        } finally {
            synchronized(gate) { progress.remove(id) }
            publish()
        }
    }

    private fun fetchIndex(): List<StcatCatalogEntry> {
        val request = Request.Builder()
            .url(indexUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "ScanTrader")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string() ?: error("Elenco vuoto")
            return PokemonCatalogIndex.parse(body)
        }
    }

    private fun downloadTo(url: String, dest: File, hintedSize: Long, onFraction: (Float) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ScanTrader")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("Risposta vuota")
            val total = body.contentLength().takeIf { it > 0 } ?: hintedSize.takeIf { it > 0 } ?: -1L
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var readTotal = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        readTotal += n
                        val now = System.currentTimeMillis()
                        if (total > 0 && (now - lastEmit > 200 || readTotal >= total)) {
                            onFraction((readTotal.toFloat() / total).coerceIn(0f, 1f))
                            lastEmit = now
                        }
                    }
                }
            }
        }
    }

    private fun messageFor(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            t is IOException -> "Connessione assente o download interrotto."
            raw.contains("checksum") -> "File danneggiato (checksum)"
            raw.startsWith("HTTP ") -> "Download non riuscito ($raw)."
            raw.isNotBlank() -> raw
            else -> "Download non riuscito."
        }
    }

    private fun setProgress(id: String, fraction: Float) {
        synchronized(gate) { progress[id] = fraction.coerceIn(0f, 1f) }
        publish()
    }

    private fun setError(id: String, text: String) {
        synchronized(gate) { errors[id] = text }
        publish()
    }

    private fun publish() {
        val snapshot = synchronized(gate) {
            val installed = library.listInstalled().associateBy { it.id }
            val rows = ArrayList<PokemonCatalogRow>()
            val seen = HashSet<String>()
            for (entry in remote) {
                seen += entry.id
                val local = installed[entry.id]
                val onDisk = local != null
                rows += PokemonCatalogRow(
                    id = entry.id,
                    name = entry.name,
                    remoteVersion = entry.version.takeIf { it.isNotBlank() },
                    remoteSize = entry.size.takeIf { it > 0L },
                    installedVersion = local?.version?.takeIf { it.isNotBlank() },
                    installedSize = local?.size?.takeIf { it > 0L },
                    installed = onDisk,
                    updateAvailable = PokemonCatalogIndex.isUpdate(entry.version, local?.version, onDisk),
                    downloading = progress.containsKey(entry.id),
                    progress = progress[entry.id] ?: 0f,
                    error = errors[entry.id],
                    cardCount = local?.cardCount ?: 0,
                )
            }
            for ((id, local) in installed) {
                if (!seen.add(id)) continue
                rows += PokemonCatalogRow(
                    id = id,
                    name = local.name.ifBlank { id },
                    remoteVersion = null,
                    remoteSize = null,
                    installedVersion = local.version.takeIf { it.isNotBlank() },
                    installedSize = local.size.takeIf { it > 0L },
                    installed = true,
                    updateAvailable = false,
                    downloading = progress.containsKey(id),
                    progress = progress[id] ?: 0f,
                    error = errors[id],
                    cardCount = local.cardCount,
                )
            }
            PokemonCatalogUi(
                rows = rows,
                checking = checking,
                message = message,
                anyInstalled = library.hasAny(),
                promptCatalogs = remote.map { it.name to it.size },
            )
        }
        _ui.value = snapshot
    }

    companion object {
        const val OFFLINE = "Connessione assente o elenco non raggiungibile."
    }
}
