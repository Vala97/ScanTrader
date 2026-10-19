package com.scantrader.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.scantrader.app.ScanTraderApp
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CollectionEntity
import com.scantrader.app.data.db.CollectionWithCount
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.prefs.ScanDefaults
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(private val app: ScanTraderApp) : ViewModel() {
    val collections: StateFlow<List<CollectionWithCount>> =
        app.repository.collections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { app.repository.ensureDefaultCollection() }
    }

    fun create(name: String) {
        viewModelScope.launch { app.repository.createCollection(name) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { app.repository.deleteCollection(id) }
    }

    companion object {
        fun factory(app: ScanTraderApp) = factoryOf { HomeViewModel(app) }
    }
}

class CollectionViewModel(
    private val app: ScanTraderApp,
    private val collectionId: Long,
) : ViewModel() {
    val collection: StateFlow<CollectionEntity?> =
        app.repository.collection(collectionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val cards: StateFlow<List<CardEntryEntity>> =
        app.repository.cards(collectionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuantity(entry: CardEntryEntity, quantity: Int) {
        viewModelScope.launch { app.repository.updateQuantity(entry, quantity) }
    }

    fun setCondition(entry: CardEntryEntity, condition: CardCondition) {
        viewModelScope.launch { app.repository.updateCondition(entry, condition) }
    }

    fun delete(entry: CardEntryEntity) {
        viewModelScope.launch { app.repository.deleteCard(entry) }
    }

    companion object {
        fun factory(app: ScanTraderApp, id: Long) = factoryOf { CollectionViewModel(app, id) }
    }
}

class SettingsViewModel(private val app: ScanTraderApp) : ViewModel() {
    val defaults: StateFlow<ScanDefaults> =
        app.prefs.defaults.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ScanDefaults(CardLanguage.ENGLISH, CardCondition.NEAR_MINT),
        )
    val neuralPackState = app.neuralPack.state

    fun setLanguage(language: CardLanguage) {
        viewModelScope.launch { app.prefs.setLanguage(language) }
    }

    fun setCondition(condition: CardCondition) {
        viewModelScope.launch { app.prefs.setCondition(condition) }
    }

    fun downloadNeuralPack() {
        viewModelScope.launch {
            app.neuralPack.downloadAndInstall()
        }
    }

    fun deleteNeuralPack() {
        app.neuralPack.delete()
    }

    fun loadNeuralPack() {
        viewModelScope.launch { app.neuralPack.ensureLoaded() }
    }

    companion object {
        fun factory(app: ScanTraderApp) = factoryOf { SettingsViewModel(app) }
    }
}

class ScanViewModel(private val app: ScanTraderApp) : ViewModel() {
    val processing = MutableStateFlow(false)
    val game = MutableStateFlow(app.scanSession.game)
    val sessionMode = MutableStateFlow(app.scanSession.sessionMode)
    val autoScan = MutableStateFlow(app.scanSession.autoScan)
    val autoStatus = MutableStateFlow<String?>(null)
    val torchOn = MutableStateFlow(app.scanSession.torchOn)
    val lightIntensity = MutableStateFlow(app.scanSession.lightIntensity)

    @Volatile
    private var lastAutoAttemptMs: Long = 0

    private val autoBusy = AtomicBoolean(false)
    private val autoHitPending = AtomicBoolean(false)

    fun setGame(value: Game) {
        game.value = value
        app.scanSession.game = value
    }

    fun setSession(value: Boolean) {
        sessionMode.value = value
        app.scanSession.sessionMode = value
    }

    fun setAutoScan(value: Boolean) {
        autoScan.value = value
        app.scanSession.autoScan = value
        autoStatus.value = if (value) "Inquadra la carta…" else null
        autoHitPending.set(false)
        autoBusy.set(false)
        lastAutoAttemptMs = 0
    }

    fun setTorchOn(value: Boolean) {
        torchOn.value = value
        app.scanSession.torchOn = value
    }

    fun setLightIntensity(value: Float) {
        lightIntensity.value = value
        app.scanSession.lightIntensity = value
    }

    fun resumeScanning() {
        autoHitPending.set(false)
        autoBusy.set(false)
        if (autoScan.value) {
            autoStatus.value = "Inquadra la carta…"
        }
    }

    fun setTargetCollection(id: Long?) {
        app.scanSession.targetCollectionId = id
    }

    fun canAcceptAutoFrame(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!autoScan.value || autoHitPending.get() || autoBusy.get() || processing.value) return false
        return nowMs - lastAutoAttemptMs >= AUTO_COOLDOWN_MS
    }

    fun processBitmap(
        bitmap: android.graphics.Bitmap,
        panelExpanded: Boolean = false,
        onDone: (ok: Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            processing.value = true
            autoStatus.value = null
            try {
                analyze(bitmap, requireMatch = false, panelExpanded = panelExpanded)
                onDone(true)
            } catch (t: Throwable) {
                app.scanSession.errorMessage = t.message ?: "Errore durante lo scan"
                onDone(true)
            } finally {
                processing.value = false
            }
        }
    }

    fun processAutoFrame(
        bitmap: android.graphics.Bitmap,
        panelExpanded: Boolean = false,
        onHit: () -> Unit,
    ) {
        val now = System.currentTimeMillis()
        if (!canAcceptAutoFrame(now)) return
        if (!autoBusy.compareAndSet(false, true)) return
        lastAutoAttemptMs = now
        viewModelScope.launch {
            processing.value = true
            autoStatus.value = "Analisi in corso…"
            try {
                val matched = analyze(bitmap, requireMatch = true, panelExpanded = panelExpanded)
                if (matched) {
                    autoHitPending.set(true)
                    autoStatus.value = "Carta trovata"
                    onHit()
                } else {
                    autoStatus.value = "Inquadra meglio…"
                }
            } catch (_: Throwable) {
                autoStatus.value = "Inquadra meglio…"
            } finally {
                processing.value = false
                if (!autoHitPending.get()) {
                    autoBusy.set(false)
                }
            }
        }
    }

    private suspend fun analyze(
        bitmap: android.graphics.Bitmap,
        requireMatch: Boolean,
        panelExpanded: Boolean,
    ): Boolean {
        app.scanSession.resetResults()
        app.scanSession.game = game.value
        return withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            com.scantrader.app.scan.ScanDebug.d(
                "analyze start game=${game.value} requireMatch=$requireMatch " +
                    "bitmap=${bitmap.width}x${bitmap.height} panelExpanded=$panelExpanded " +
                    "neuralInstalled=${app.neuralPack.isInstalled()} neuralReady=${app.neuralPack.isReady()}",
            )
            val cropped = com.scantrader.app.scan.CardFrameGeometry.cropBitmap(bitmap, panelExpanded)
            val fingerprint = runCatching {
                com.scantrader.app.vision.ImageFingerprint.fromCardCrop(cropped)
            }.onFailure { com.scantrader.app.scan.ScanDebug.w("fingerprint failed", it) }
                .getOrNull()
            val text = app.ocr.recognize(cropped, cjk = game.value == com.scantrader.app.data.model.Game.POKEMON)
            // Bottom strip holds set+number for both MTG and Pokémon.
            val bottomText = runCatching {
                app.ocr.recognize(com.scantrader.app.scan.CardFrameGeometry.bottomStrip(cropped))
            }.onFailure { com.scantrader.app.scan.ScanDebug.w("bottom OCR failed", it) }
                .getOrDefault("")
            val parsed = com.scantrader.app.scan.OcrParser.parse(text, game.value, bottomText)
            com.scantrader.app.scan.ScanDebug.d(
                "OCR fullChars=${text.length} bottomChars=${bottomText.length} " +
                    "parsed name=${parsed.nameGuess} cn=${parsed.collectorNumber} " +
                    "set=${parsed.setCode} count=${parsed.setCardCount}",
            )
            if (text.isNotBlank()) {
                com.scantrader.app.scan.ScanDebug.d("OCR full snippet=${text.take(180).replace("\n", " | ")}")
            }
            if (bottomText.isNotBlank()) {
                com.scantrader.app.scan.ScanDebug.d("OCR bottom snippet=${bottomText.take(120).replace("\n", " | ")}")
            }
            val (catalogHits, galleryHits) = coroutineScope {
                val ocrJob = async {
                    runCatching { app.catalog.identify(game.value, parsed) }
                        .onFailure { com.scantrader.app.scan.ScanDebug.e("catalog.identify failed", it) }
                        .getOrDefault(emptyList())
                }
                val galleryJob = async {
                    if (game.value != Game.MTG) {
                        com.scantrader.app.scan.ScanDebug.d("gallery skipped (not MTG)")
                        emptyList()
                    } else if (!app.neuralPack.isInstalled()) {
                        com.scantrader.app.scan.ScanDebug.d("gallery skipped (neural pack not installed)")
                        emptyList()
                    } else {
                        runCatching { app.galleryMatcher.matchMtg(cropped, parsed) }
                            .onFailure { com.scantrader.app.scan.ScanDebug.e("gallery.matchMtg failed", it) }
                            .getOrDefault(emptyList())
                    }
                }
                ocrJob.await() to galleryJob.await()
            }
            com.scantrader.app.scan.ScanDebug.d(
                "catalogHits=${catalogHits.size} galleryHits=${galleryHits.size} " +
                    "galleryTop=${galleryHits.firstOrNull()?.let { "${it.name} v=${it.visualScore}" }}",
            )
            val fingerprintRanked = if (fingerprint != null && catalogHits.isNotEmpty()) {
                runCatching { app.visualRanker.rank(fingerprint, catalogHits) }
                    .onFailure { com.scantrader.app.scan.ScanDebug.w("visualRanker failed", it) }
                    .getOrDefault(catalogHits)
            } else {
                catalogHits
            }
            val merged = com.scantrader.app.vision.mergeHybridCandidates(fingerprintRanked, galleryHits)
            val candidates = com.scantrader.app.vision.MatchFusion.fuse(merged, parsed)
                .distinctBy { it.scryfallId ?: "${it.setCode}-${it.collectorNumber}-${it.name}" }
                .take(12)
            val top = candidates.firstOrNull()
            val autoOk = com.scantrader.app.vision.MatchFusion.isConfidentAutoMatch(top)
            com.scantrader.app.scan.ScanDebug.d(
                "result candidates=${candidates.size} top=${top?.name} " +
                    "set=${top?.setCode} cn=${top?.collectorNumber} " +
                    "ocr=${top?.ocrScore} visual=${top?.visualScore} conf=${top?.matchConfidence} " +
                    "autoOk=$autoOk requireMatch=$requireMatch ms=${System.currentTimeMillis() - t0}",
            )
            app.scanSession.ocrText = buildString {
                append(text)
                if (bottomText.isNotBlank()) {
                    append("\n--- bottom ---\n")
                    append(bottomText)
                }
            }
            app.scanSession.parsed = parsed
            app.scanSession.queryFingerprint = fingerprint
            app.scanSession.candidates = candidates
            if (candidates.isEmpty() && text.isBlank() && bottomText.isBlank()) {
                app.scanSession.errorMessage =
                    "Nessun testo letto. Inquadra tutta la carta, soprattutto il basso a sinistra (numero + set)."
            } else if (candidates.isEmpty()) {
                app.scanSession.errorMessage =
                    "Carta non trovata. Verifica numero e set in basso a sinistra, oppure cerca manualmente."
            } else {
                app.scanSession.errorMessage = null
            }
            if (requireMatch) autoOk else true
        }
    }

    companion object {
        private const val AUTO_COOLDOWN_MS = 1_400L

        fun factory(app: ScanTraderApp) = factoryOf { ScanViewModel(app) }
    }
}

class ConfirmViewModel(private val app: ScanTraderApp) : ViewModel() {
    val collections: StateFlow<List<CollectionEntity>> =
        app.repository.collectionList().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val defaults: StateFlow<ScanDefaults> =
        app.prefs.defaults.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ScanDefaults(CardLanguage.ENGLISH, CardCondition.NEAR_MINT),
        )
    val candidates = MutableStateFlow(app.scanSession.candidates)
    val searching = MutableStateFlow(false)
    val parsed get() = app.scanSession.parsed
    val errorMessage get() = app.scanSession.errorMessage

    fun reload() {
        candidates.value = app.scanSession.candidates
    }

    fun search(query: String) {
        viewModelScope.launch {
            searching.value = true
            try {
                val parsedText = app.scanSession.parsed
                    ?: com.scantrader.app.data.model.ParsedCardText(query, null, null)
                val hits = app.catalog.identify(app.scanSession.game, parsedText, query)
                val fingerprint = app.scanSession.queryFingerprint
                val ranked = if (fingerprint != null && hits.isNotEmpty()) {
                    runCatching { app.visualRanker.rank(fingerprint, hits) }.getOrDefault(hits)
                } else {
                    hits
                }
                val fused = com.scantrader.app.vision.MatchFusion.fuse(ranked, parsedText)
                candidates.value = fused
                app.scanSession.candidates = fused
            } catch (_: Throwable) {
                candidates.value = emptyList()
            } finally {
                searching.value = false
            }
        }
    }

    fun save(
        card: IdentifiedCard,
        collectionId: Long,
        quantity: Int,
        language: CardLanguage,
        condition: CardCondition,
        foil: Boolean,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            app.repository.saveCard(collectionId, card, quantity, language, condition, foil)
            app.scanSession.targetCollectionId = collectionId
            onDone()
        }
    }

    companion object {
        fun factory(app: ScanTraderApp) = factoryOf { ConfirmViewModel(app) }
    }
}

inline fun <reified VM : ViewModel> factoryOf(crossinline create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
