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
import com.scantrader.app.data.catalog.CatalogQueryDebounce
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.data.prefs.ScanDefaults
import com.scantrader.app.network.scryfall.ScryfallBusyException
import com.scantrader.app.scan.BackCamera
import com.scantrader.app.scan.CameraDevices
import com.scantrader.app.scan.LensCatalog
import com.scantrader.app.scan.LensKind
import com.scantrader.app.scan.LensOption
import com.scantrader.app.scan.ZoomFraming
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
    val neuralPackState = app.neuralPack.mtgState
    val pokemonPackState = app.neuralPack.pokemonState
    val lensOptions = MutableStateFlow(CameraDevices.lensOptions(app))

    fun refreshLenses() {
        val fresh = CameraDevices.lensOptions(app)
        if (fresh.isEmpty() && lensOptions.value.isNotEmpty()) return
        lensOptions.value = fresh
    }

    fun setLanguage(language: CardLanguage) {
        viewModelScope.launch { app.prefs.setLanguage(language) }
    }

    fun setCondition(condition: CardCondition) {
        viewModelScope.launch { app.prefs.setCondition(condition) }
    }

    fun setLens(option: LensOption) {
        app.scanSession.lensKey = option.key
        app.scanSession.cameraId = option.cameraId
        val zoom = when (option.kind) {
            LensKind.ZOOM -> option.zoomRatio
            LensKind.CAMERA -> 1f
            LensKind.AUTOMATIC -> null
        }
        if (zoom != null) app.scanSession.zoomRatio = zoom
        viewModelScope.launch { app.prefs.setLensChoice(option.key, option.cameraId, zoom) }
    }

    fun setAutoSaveWaitSeconds(seconds: Int) {
        viewModelScope.launch { app.prefs.setAutoSaveWaitSeconds(seconds) }
    }

    fun setDetectCardLanguage(enabled: Boolean) {
        viewModelScope.launch { app.prefs.setDetectCardLanguage(enabled) }
    }

    val hasCardTraderToken = MutableStateFlow(app.secrets.hasCardTraderToken())

    fun saveCardTraderToken(token: String) {
        app.secrets.setCardTraderToken(token)
        hasCardTraderToken.value = app.secrets.hasCardTraderToken()
    }

    fun clearCardTraderToken() {
        app.secrets.setCardTraderToken(null)
        hasCardTraderToken.value = false
    }

    fun downloadNeuralPack() {
        viewModelScope.launch {
            app.neuralPack.downloadAndInstall()
        }
    }

    fun deleteNeuralPack() {
        app.neuralPack.delete(Game.MTG)
    }

    fun deletePokemonPack() {
        app.neuralPack.delete(Game.POKEMON)
    }

    fun importPokemonPack(uri: android.net.Uri) {
        viewModelScope.launch {
            app.neuralPack.importPokemonCatalog(uri)
        }
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
    val mtgPackState = app.neuralPack.mtgState
    val torchOn = MutableStateFlow(app.scanSession.torchOn)
    val lightIntensity = MutableStateFlow(app.scanSession.lightIntensity)
    val zoomRatio = MutableStateFlow(app.scanSession.zoomRatio)
    val cameraId = MutableStateFlow(app.scanSession.cameraId)
    val lensKey = MutableStateFlow(app.scanSession.lensKey)
    val guideViewWidth = MutableStateFlow(app.scanSession.guideViewWidth)
    val guideViewHeight = MutableStateFlow(app.scanSession.guideViewHeight)
    private var hardware: List<BackCamera> = CameraDevices.backCameras(app)
    private var zoomRangeLive: Boolean = false
    val lensOptions = MutableStateFlow(LensCatalog.optionsFor(hardware))

    @Volatile
    private var lastAutoAttemptMs: Long = 0

    /** Identity of the last auto-accepted card — block re-scan until a different card appears. */
    @Volatile
    private var lastAcceptedCardKey: String? = null

    /** Consecutive frames where the locked card is no longer the top hit. */
    @Volatile
    private var sameCardClearFrames: Int = 0

    private val autoBusy = AtomicBoolean(false)
    private val autoHitPending = AtomicBoolean(false)
    private val catalogDebounce = CatalogQueryDebounce()
    private var autoHold = AutoHold.NONE

    private var zoomPersistJob: kotlinx.coroutines.Job? = null
    private var zoomLockJob: kotlinx.coroutines.Job? = null
    private var zoomLockGen: Int = 0

    /** While set, pinch and zoom-state callbacks must not overwrite a lens preset. */
    @Volatile
    private var programmaticZoom: Float? = null

    /**
     * True from the moment a rebind starts until the camera reports the stored
     * ratio. Default 1× callbacks in that window are not a user pinch.
     */
    @Volatile
    private var zoomRestoreArmed: Boolean = false

    /**
     * Set when leaving the scanner for confirm, before CameraX can report the
     * unbind zoom. Cleared only once the stored ratio is observed again.
     */
    @Volatile
    private var zoomWritesHeld: Boolean = false

    /** Set once prefs or a user zoom/lens choice have defined the framing. */
    @Volatile
    private var framingEstablished: Boolean = false

    private val userPickedLens = AtomicBoolean(false)
    private val zoomTouched = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            val defaults = app.prefs.currentDefaults()
            if (!userPickedLens.get() && !zoomTouched.get()) {
                val resolved = LensCatalog.resolve(
                    lensOptions.value,
                    defaults.lensKey ?: app.scanSession.lensKey,
                    defaults.cameraId ?: app.scanSession.cameraId,
                )
                if (resolved != null) {
                    if (resolved.kind != LensKind.ZOOM && defaults.zoomRatio > 0f) {
                        zoomRatio.value = defaults.zoomRatio
                        app.scanSession.zoomRatio = defaults.zoomRatio
                    }
                    applyLens(resolved, fromUser = false)
                }
            }
            framingEstablished = true
        }
    }

    /**
     * Call once when the scan screen is composed again. The ViewModel and the
     * session already hold the lens and zoom; this only blocks the post-bind
     * 1× report from replacing them.
     */
    fun armZoomRestore() {
        if (!framingEstablished) return
        val ratio = ZoomFraming.clamp(app.scanSession.zoomRatio, min = Float.NaN, max = Float.NaN) ?: return
        zoomRatio.value = ratio
        val lens = app.scanSession.lensKey
        if (lensKey.value != lens) lensKey.value = lens
        if (cameraId.value != app.scanSession.cameraId) cameraId.value = app.scanSession.cameraId
        // Cancel a lens-lock timer so it cannot drop the restore guard.
        // Do not start a timeout: a late 1× must not replace the session zoom.
        zoomLockGen++
        zoomLockJob?.cancel()
        zoomRestoreArmed = true
        zoomWritesHeld = true
        programmaticZoom = ratio
    }

    fun isZoomRestoreArmed(): Boolean = zoomRestoreArmed

    fun isFramingHeld(): Boolean = zoomWritesHeld || zoomRestoreArmed

    /** Confirm is covering the scanner. Ignore zoom reports until we restore. */
    fun holdFraming() {
        zoomWritesHeld = true
    }

    /** Stored ratio was observed after CameraControl and zoomState were ready. */
    fun onZoomRestoreSettled() {
        zoomRestoreArmed = false
        zoomWritesHeld = false
        programmaticZoom = null
    }

    /** First real scanner layout. Later sizes must not move the guide. */
    fun lockGuideViewport(width: Float, height: Float) {
        if (width <= 1f || height <= 1f) return
        if (app.scanSession.guideViewWidth > 1f && app.scanSession.guideViewHeight > 1f) return
        app.scanSession.guideViewWidth = width
        app.scanSession.guideViewHeight = height
        guideViewWidth.value = width
        guideViewHeight.value = height
    }

    fun setGame(value: Game) {
        game.value = value
        app.scanSession.game = value
    }

    /** Game the user already confirmed for this scan visit. Survives confirm. */
    val isGameChosen: Boolean get() = app.scanSession.gameChosen

    fun chooseGame(value: Game) {
        setGame(value)
        app.scanSession.gameChosen = true
    }

    override fun onCleared() {
        // Leaving the scan destination (not a trip to confirm) asks for the game again.
        app.scanSession.gameChosen = false
        super.onCleared()
    }

    fun selectLens(option: LensOption) {
        applyLens(option, fromUser = true)
    }

    fun currentLensOption(): LensOption? =
        lensOptions.value.firstOrNull { it.key == lensKey.value }

    fun refreshHardware() {
        val fresh = CameraDevices.backCameras(app)
        if (fresh.isEmpty()) return
        hardware = if (
            zoomRangeLive &&
            hardware.size == 1 &&
            fresh.size == 1 &&
            hardware[0].id == fresh[0].id
        ) {
            listOf(
                fresh[0].copy(
                    minZoomRatio = hardware[0].minZoomRatio,
                    maxZoomRatio = hardware[0].maxZoomRatio,
                ),
            )
        } else {
            fresh
        }
        republishLenses()
    }

    /** Live CameraX range. Used when a single logical camera supplies the presets. */
    fun updateZoomRange(min: Float, max: Float) {
        if (hardware.size != 1) return
        if (!min.isFinite() || !max.isFinite() || min <= 0f || max < min) return
        val cam = hardware[0]
        if (kotlin.math.abs(cam.minZoomRatio - min) < 0.02f &&
            kotlin.math.abs(cam.maxZoomRatio - max) < 0.02f
        ) {
            return
        }
        hardware = listOf(cam.copy(minZoomRatio = min, maxZoomRatio = max))
        zoomRangeLive = true
        republishLenses()
    }

    fun onObservedZoom(reported: Float) {
        if (!reported.isFinite() || reported <= 0f) return
        val stored = programmaticZoom ?: zoomRatio.value
        if (!ZoomFraming.acceptReportedZoom(stored, reported, restorePending = isFramingHeld())) {
            return
        }
        if (isFramingHeld()) {
            onZoomRestoreSettled()
            return
        }
        val pending = programmaticZoom
        if (pending != null) {
            if (ZoomFraming.matches(reported, pending)) programmaticZoom = null
            return
        }
        setZoomRatio(reported)
    }

    /** Pause/dispose path. Ignores the 1× jump CameraX emits while unbinding. */
    fun persistCameraZoom(reported: Float) {
        if (isFramingHeld()) return
        if (!ZoomFraming.acceptReportedZoom(zoomRatio.value, reported, restorePending = false)) return
        onObservedZoom(reported)
    }

    fun noteZoomClamped(requested: Float, applied: Float) {
        if (programmaticZoom == null) return
        if (!applied.isFinite() || applied <= 0f) return
        if (kotlin.math.abs(requested - applied) < 0.01f) return
        programmaticZoom = applied
        if (kotlin.math.abs(zoomRatio.value - applied) >= 0.01f) {
            zoomRatio.value = applied
            app.scanSession.zoomRatio = applied
        }
    }

    private fun republishLenses() {
        val opts = LensCatalog.optionsFor(hardware)
        lensOptions.value = opts
        val key = lensKey.value
        if (!key.isNullOrBlank() && opts.none { it.key == key }) {
            val fallback = LensCatalog.resolve(opts, null, null) ?: return
            applyLens(fallback, fromUser = false)
        }
    }

    private fun applyLens(option: LensOption, fromUser: Boolean) {
        if (fromUser) {
            userPickedLens.set(true)
            framingEstablished = true
        }
        val zoom = when (option.kind) {
            LensKind.ZOOM -> option.zoomRatio
            LensKind.CAMERA -> if (fromUser) 1f else null
            LensKind.AUTOMATIC -> null
        }
        if (zoom != null) lockZoom(zoom)
        cameraId.value = option.cameraId
        app.scanSession.cameraId = option.cameraId
        lensKey.value = option.key
        app.scanSession.lensKey = option.key
        zoomPersistJob?.cancel()
        viewModelScope.launch { app.prefs.setLensChoice(option.key, option.cameraId, zoom) }
    }

    private fun lockZoom(target: Float) {
        zoomRestoreArmed = false
        zoomWritesHeld = false
        programmaticZoom = target
        zoomRatio.value = target
        app.scanSession.zoomRatio = target
        framingEstablished = true
        val gen = ++zoomLockGen
        zoomLockJob?.cancel()
        zoomLockJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1_500)
            if (gen == zoomLockGen) programmaticZoom = null
        }
    }

    fun setSession(value: Boolean) {
        sessionMode.value = value
        app.scanSession.sessionMode = value
    }

    fun setAutoScan(value: Boolean) {
        autoScan.value = value
        app.scanSession.autoScan = value
        autoHitPending.set(false)
        autoBusy.set(false)
        lastAutoAttemptMs = 0
        clearSameCardLock()
        autoStatus.value = if (value) "Inquadra la carta…" else null
    }

    fun setTorchOn(value: Boolean) {
        torchOn.value = value
        app.scanSession.torchOn = value
    }

    fun setLightIntensity(value: Float) {
        lightIntensity.value = value
        app.scanSession.lightIntensity = value
    }

    fun setZoomRatio(value: Float) {
        if (!value.isFinite() || value <= 0f) return
        framingEstablished = true
        zoomTouched.set(true)
        if (kotlin.math.abs(zoomRatio.value - value) < 0.01f) return
        zoomRatio.value = value
        app.scanSession.zoomRatio = value
        // Debounce DataStore writes during pinch-zoom.
        zoomPersistJob?.cancel()
        zoomPersistJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            app.prefs.setZoomRatio(value)
        }
    }

    fun resumeScanning() {
        autoHitPending.set(false)
        autoBusy.set(false)
        // Keep lastAcceptedCardKey so the same physical card still in frame is ignored.
        if (autoScan.value) {
            autoStatus.value = if (lastAcceptedCardKey != null) {
                "Metti un’altra carta…"
            } else {
                "Inquadra la carta…"
            }
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
        onDone: (ok: Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            processing.value = true
            autoStatus.value = null
            try {
                analyze(bitmap, requireMatch = false)
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
        onHit: () -> Unit,
    ) {
        val now = System.currentTimeMillis()
        if (!canAcceptAutoFrame(now)) return
        if (!autoBusy.compareAndSet(false, true)) return
        viewModelScope.launch {
            processing.value = true
            autoStatus.value = "Analisi in corso…"
            try {
                autoHold = AutoHold.NONE
                val matched = analyze(bitmap, requireMatch = true)
                if (matched) {
                    autoHitPending.set(true)
                    autoStatus.value = "Carta trovata"
                    onHit()
                } else when (autoHold) {
                    AutoHold.BUSY -> autoStatus.value = "Scryfall occupato, riprovo…"
                    AutoHold.UNSTABLE -> autoStatus.value = if (lastAcceptedCardKey != null) {
                        "Metti un’altra carta…"
                    } else {
                        "Inquadra la carta…"
                    }
                    AutoHold.NONE -> autoStatus.value = if (lastAcceptedCardKey != null) {
                        "Metti un’altra carta…"
                    } else {
                        "Inquadra meglio…"
                    }
                }
            } catch (_: Throwable) {
                autoStatus.value = if (lastAcceptedCardKey != null) {
                    "Metti un’altra carta…"
                } else {
                    "Inquadra meglio…"
                }
            } finally {
                // Cooldown from end of analysis so long OCR doesn't stack extra wait.
                lastAutoAttemptMs = System.currentTimeMillis()
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
    ): Boolean {
        app.scanSession.resetResults()
        app.scanSession.game = game.value
        val autoMode = requireMatch
        return withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            com.scantrader.app.scan.ScanDebug.d(
                "analyze start game=${game.value} requireMatch=$requireMatch " +
                    "bitmap=${bitmap.width}x${bitmap.height} " +
                    "neuralMtg=${app.neuralPack.isInstalled(Game.MTG)} " +
                    "neuralPkm=${app.neuralPack.isInstalled(Game.POKEMON)}",
            )
            val croppedFull = com.scantrader.app.scan.CardFrameGeometry.cropBitmap(bitmap)
            // Auto: lighter crop; manual stills stay higher-res (often 4K).
            val cropped = downscaleForOcr(
                croppedFull,
                maxSide = if (autoMode) AUTO_OCR_MAX_SIDE else MANUAL_OCR_MAX_SIDE,
            )
            com.scantrader.app.scan.ScanDebug.d(
                "crop ${cropped.width}x${cropped.height} from ${bitmap.width}x${bitmap.height}" +
                    if (cropped !== croppedFull) " (downscaled)" else "",
            )
            val isMtg = game.value == Game.MTG
            val isPokemon = game.value == Game.POKEMON
            val tOcr0 = System.currentTimeMillis()
            // Parallel OCR: top + bottom-left first (identity). Full card only if needed.
            val (topNameText, bottomLeft, text, bottomFull, bottomRight, bottomEdge) =
                coroutineScope {
                    // Auto: skip 2.5× enhance on first pass; manual keeps enhance for tiny type.
                    val enhanceFirst = !autoMode
                    val topJob = async {
                        runCatching {
                            app.ocr.recognize(
                                com.scantrader.app.scan.CardFrameGeometry.topNameStrip(cropped, mtg = isMtg),
                                enhance = enhanceFirst,
                            )
                        }.getOrDefault("")
                    }
                    val blJob = async {
                        runCatching {
                            val strip = if (isMtg) {
                                com.scantrader.app.scan.CardFrameGeometry.bottomBandStrip(cropped)
                            } else {
                                com.scantrader.app.scan.CardFrameGeometry.bottomLeftStrip(cropped)
                            }
                            app.ocr.recognize(strip, enhance = enhanceFirst)
                        }.getOrDefault("")
                    }
                    var top = topJob.await()
                    var bl = blJob.await()
                    var quick = com.scantrader.app.scan.OcrParser.parse(
                        rawText = top,
                        game = game.value,
                        bottomPriorityText = bl,
                        namePriorityText = top.takeIf { it.isNotBlank() },
                    )
                    // Auto: one enhanced pass when the collector is still missing.
                    // Old MTG frames have a number and no set code — don't re-OCR those.
                    val needsEnhance = if (isMtg) {
                        quick.collectorNumber.isNullOrBlank()
                    } else {
                        quick.collectorNumber.isNullOrBlank() || quick.setCode.isNullOrBlank()
                    }
                    if (autoMode && needsEnhance) {
                        bl = runCatching {
                            val strip = if (isMtg) {
                                com.scantrader.app.scan.CardFrameGeometry.bottomBandStrip(cropped)
                            } else {
                                com.scantrader.app.scan.CardFrameGeometry.bottomLeftStrip(cropped)
                            }
                            app.ocr.recognize(strip, enhance = true)
                        }.getOrDefault(bl)
                        quick = com.scantrader.app.scan.OcrParser.parse(
                            rawText = top,
                            game = game.value,
                            bottomPriorityText = bl,
                            namePriorityText = top.takeIf { it.isNotBlank() },
                        )
                    }
                    val identityReady = com.scantrader.app.scan.OcrParser.identityReadyForFastPath(quick)
                    val quickId = catalogIdentity(quick)
                    val nameAndCnStable = autoMode &&
                        !quick.nameGuess.isNullOrBlank() &&
                        !quick.collectorNumber.isNullOrBlank() &&
                        catalogDebounce.seenThisBefore(quickId)
                    if (autoMode && top.isBlank() && bl.isBlank()) {
                        com.scantrader.app.scan.ScanDebug.d("OCR early-abort empty strips")
                        HexOcrBundle(top, bl, "", "", "", "")
                    } else if (identityReady || nameAndCnStable) {
                        com.scantrader.app.scan.ScanDebug.d(
                            if (identityReady) "OCR fast-path identity ready" else "OCR skip extra passes (name+cn stable)",
                        )
                        HexOcrBundle(top, bl, top, bl, "", "")
                    } else {
                        val fullJob = async {
                            runCatching {
                                // Auto Pokémon: latin-only full pass; CJK only on manual.
                                app.ocr.recognize(cropped, cjk = isPokemon && !autoMode)
                            }.getOrDefault("")
                        }
                        val bfJob = async {
                            runCatching {
                                app.ocr.recognize(
                                    com.scantrader.app.scan.CardFrameGeometry.bottomStrip(cropped),
                                )
                            }.getOrDefault("")
                        }
                        val brJob = async {
                            if (!isPokemon) {
                                ""
                            } else {
                                runCatching {
                                    app.ocr.recognize(
                                        com.scantrader.app.scan.CardFrameGeometry.bottomRightStrip(cropped),
                                        enhance = true,
                                    )
                                }.getOrDefault("")
                            }
                        }
                        val beJob = async {
                            if (!isPokemon) {
                                ""
                            } else {
                                runCatching {
                                    app.ocr.recognize(
                                        com.scantrader.app.scan.CardFrameGeometry.bottomEdgeStrip(cropped),
                                        enhance = true,
                                    )
                                }.getOrDefault("")
                            }
                        }
                        HexOcrBundle(
                            top = top,
                            bl = bl,
                            text = fullJob.await(),
                            bottomFull = bfJob.await(),
                            bottomRight = brJob.await(),
                            bottomEdge = beJob.await(),
                        )
                    }
                }
            val ocrMs = System.currentTimeMillis() - tOcr0
            // MTG: bottom-left first (set+number). Pokémon: edge/right first for vintage, left last.
            val bottomText = buildString {
                if (isMtg) {
                    if (bottomLeft.isNotBlank()) append(bottomLeft).append("\n")
                    if (bottomFull.isNotBlank() && bottomFull != bottomLeft) append(bottomFull)
                } else {
                    if (bottomEdge.isNotBlank()) append(bottomEdge).append("\n")
                    if (bottomRight.isNotBlank()) append(bottomRight).append("\n")
                    append(bottomFull)
                    if (bottomLeft.isNotBlank()) append("\n").append(bottomLeft)
                }
            }
            val parsed = com.scantrader.app.scan.OcrParser.parse(
                rawText = text,
                game = game.value,
                bottomPriorityText = bottomText,
                namePriorityText = topNameText.takeIf { it.isNotBlank() },
            )
            com.scantrader.app.scan.ScanDebug.d(
                "OCR fullChars=${text.length} topChars=${topNameText.length} " +
                    "bottomChars=${bottomText.length} bl=${bottomLeft.length} br=${bottomRight.length} " +
                    "parsed name=${parsed.nameGuess} cn=${parsed.collectorNumber} " +
                    "set=${parsed.setCode} count=${parsed.setCardCount} ocrMs=$ocrMs",
            )
            if (topNameText.isNotBlank()) {
                com.scantrader.app.scan.ScanDebug.d(
                    "OCR top snippet=${topNameText.take(100).replace("\n", " | ")}",
                )
            }
            if (text.isNotBlank()) {
                com.scantrader.app.scan.ScanDebug.d("OCR full snippet=${text.take(180).replace("\n", " | ")}")
            }
            if (bottomLeft.isNotBlank()) {
                com.scantrader.app.scan.ScanDebug.d(
                    "OCR bottom-left snippet=${bottomLeft.take(80).replace("\n", " | ")}",
                )
            }
            if (bottomRight.isNotBlank()) {
                com.scantrader.app.scan.ScanDebug.d("OCR bottom-right snippet=${bottomRight.take(80).replace("\n", " | ")}")
            } else if (bottomFull.isNotBlank()) {
                com.scantrader.app.scan.ScanDebug.d("OCR bottom snippet=${bottomFull.take(120).replace("\n", " | ")}")
            }

            // Auto: empty strips → skip network/vision entirely.
            if (autoMode && topNameText.isBlank() && bottomLeft.isBlank() &&
                text.isBlank() && bottomText.isBlank()
            ) {
                com.scantrader.app.scan.ScanDebug.d(
                    "result candidates=0 top=null autoOk=false earlyAbort " +
                        "requireMatch=$requireMatch ocrMs=$ocrMs catalogMs=0 " +
                        "ms=${System.currentTimeMillis() - t0}",
                )
                app.scanSession.errorMessage =
                    "Nessun testo letto. Inquadra tutta la carta, soprattutto il basso a sinistra (numero + set)."
                return@withContext false
            }

            val idReady = com.scantrader.app.scan.OcrParser.identityReadyForFastPath(parsed)
            val cooling = autoMode && app.scryfall.coolingDown()
            if (cooling) {
                catalogDebounce.invalidateLastQuery()
                com.scantrader.app.scan.ScanDebug.d("Scryfall cooldown")
            }
            val queryCatalog = when {
                !autoMode -> true
                cooling -> false
                else -> catalogDebounce.shouldQuery(
                    com.scantrader.app.data.catalog.CatalogQueryDebounce.Frame(
                        name = parsed.nameGuess,
                        collectorNumber = parsed.collectorNumber,
                        setCode = parsed.setCode,
                        setCardCount = parsed.setCardCount,
                        pokemon = isPokemon,
                    ),
                )
            }
            // Majority name/cn/set over the recent frames. One FASE / asch read must not
            // be what we send to TCGdex, and it must not reset the stability clock.
            val matched = if (autoMode && !cooling) catalogDebounce.voted(parsed) else parsed
            val catalogIdentity = catalogIdentity(matched)
            val neuralOn = app.neuralPack.isInstalled(game.value)
            // No catalog yet and no neural pack: wait for a second identical id.
            // With a pack installed, the gallery still runs so a visual hit can autosave.
            if (autoMode && !queryCatalog && !neuralOn) {
                autoHold = if (cooling) AutoHold.BUSY else AutoHold.UNSTABLE
                app.scanSession.parsed = matched
                com.scantrader.app.scan.ScanDebug.d(
                    "catalog skipped (OCR not stable yet) id=$catalogIdentity",
                )
                return@withContext false
            }
            if (autoMode && !queryCatalog) {
                com.scantrader.app.scan.ScanDebug.d(
                    "catalog held id=$catalogIdentity neural=true",
                )
            }
            // Auto + cn+set: catalog only (skip gallery + fingerprint).
            val skipHeavyVision = autoMode && idReady
            val fingerprint = if (skipHeavyVision || !queryCatalog) {
                com.scantrader.app.scan.ScanDebug.d("fingerprint skipped (auto id-ready)")
                null
            } else {
                runCatching {
                    com.scantrader.app.vision.ImageFingerprint.fromCardCrop(cropped)
                }.onFailure { com.scantrader.app.scan.ScanDebug.w("fingerprint failed", it) }
                    .getOrNull()
            }

            val tCat0 = System.currentTimeMillis()
            val fetched = try {
                coroutineScope {
                val ocrJob = async {
                    if (!queryCatalog) {
                        return@async emptyList()
                    }
                    try {
                        app.catalog.identify(game.value, matched)
                    } catch (busy: ScryfallBusyException) {
                        throw busy
                    } catch (t: Throwable) {
                        com.scantrader.app.scan.ScanDebug.e("catalog.identify failed", t)
                        emptyList()
                    }
                }
                val galleryJob = async {
                    if (skipHeavyVision) {
                        com.scantrader.app.scan.ScanDebug.d("gallery skipped (auto id-ready)")
                        return@async emptyList()
                    }
                    when (game.value) {
                        Game.MTG -> {
                            if (!app.neuralPack.isInstalled(Game.MTG)) {
                                com.scantrader.app.scan.ScanDebug.d("gallery skipped (MTG pack not installed)")
                                emptyList()
                            } else {
                                val printedLang = (
                                    app.scanSession.language ?: CardLanguage.ENGLISH
                                    ).scryfallLang()
                                runCatching {
                                    app.galleryMatcher.matchMtg(
                                        cropped,
                                        matched,
                                        printedLang = printedLang,
                                    )
                                }
                                    .onFailure {
                                        com.scantrader.app.scan.ScanDebug.e("gallery.matchMtg failed", it)
                                    }
                                    .getOrDefault(emptyList())
                            }
                        }
                        Game.POKEMON -> {
                            if (!app.neuralPack.isInstalled(Game.POKEMON)) {
                                com.scantrader.app.scan.ScanDebug.d("gallery skipped (Pokémon pack not installed)")
                                emptyList()
                            } else {
                                runCatching { app.galleryMatcher.matchPokemon(cropped, matched) }
                                    .onFailure {
                                        com.scantrader.app.scan.ScanDebug.e("gallery.matchPokemon failed", it)
                                    }
                                    .getOrDefault(emptyList())
                            }
                        }
                    }
                }
                ocrJob.await() to galleryJob.await()
                }
            } catch (busy: ScryfallBusyException) {
                if (!autoMode) throw busy
                catalogDebounce.invalidateLastQuery()
                autoHold = AutoHold.BUSY
                app.scanSession.parsed = matched
                com.scantrader.app.scan.ScanDebug.d("catalog stopped (Scryfall 429)")
                return@withContext false
            }
            var catalogHits = fetched.first
            val galleryHits = fetched.second
            val catalogMs = System.currentTimeMillis() - tCat0
            com.scantrader.app.scan.ScanDebug.d(
                "catalogHits=${catalogHits.size} galleryHits=${galleryHits.size} " +
                    "galleryTop=${galleryHits.firstOrNull()?.let { "${it.name} v=${it.visualScore}" }} " +
                    "catalogMs=$catalogMs",
            )
            // Gallery hit with a conflicting title: do not wait out the stability loop.
            if (autoMode && catalogHits.isEmpty() && galleryHits.isNotEmpty()) {
                val preview = com.scantrader.app.vision.MatchFusion
                    .fuse(galleryHits, matched)
                    .firstOrNull()
                val previewGate = com.scantrader.app.vision.MatchFusion.evaluateAutoMatch(preview, matched)
                if (!previewGate.accepted && previewGate.nameConflict &&
                    catalogDebounce.forceQuery(catalogIdentity)
                ) {
                    com.scantrader.app.scan.ScanDebug.d(
                        "catalog now (nameConflict) id=$catalogIdentity",
                    )
                    catalogHits = try {
                        app.catalog.identify(game.value, matched)
                    } catch (busy: ScryfallBusyException) {
                        catalogDebounce.invalidateLastQuery()
                        autoHold = AutoHold.BUSY
                        app.scanSession.parsed = matched
                        com.scantrader.app.scan.ScanDebug.d("catalog stopped (Scryfall 429)")
                        return@withContext false
                    } catch (t: Throwable) {
                        com.scantrader.app.scan.ScanDebug.e("catalog.identify failed", t)
                        emptyList()
                    }
                }
            }
            // Autosave must not wait on the card-art download. Confirm loads it with Coil.
            val fingerprintRanked = if (autoMode) {
                if (fingerprint != null && catalogHits.isNotEmpty()) {
                    com.scantrader.app.scan.ScanDebug.d("card image async (autosave)")
                }
                catalogHits
            } else if (fingerprint != null && catalogHits.isNotEmpty()) {
                runCatching { app.visualRanker.rank(fingerprint, catalogHits) }
                    .onFailure { com.scantrader.app.scan.ScanDebug.w("visualRanker failed", it) }
                    .getOrDefault(catalogHits)
            } else {
                catalogHits
            }
            val merged = com.scantrader.app.vision.mergeHybridCandidates(fingerprintRanked, galleryHits)
            val candidates = com.scantrader.app.vision.MatchFusion.fuse(merged, matched)
                .distinctBy {
                    it.scryfallId
                        ?: it.tcgdexId
                        ?: "${it.setCode}-${it.collectorNumber}-${it.name}"
                }
                .take(12)
            val top = candidates.firstOrNull()
            val gate = com.scantrader.app.vision.MatchFusion.evaluateAutoMatch(top, matched)
            val autoOk = gate.accepted
            val topKey = top?.let { autoCardKey(it) }
            val blockedSameCard = requireMatch &&
                autoOk &&
                topKey != null &&
                topKey == lastAcceptedCardKey
            // Unlock only after a few frames where the locked card is gone / different.
            if (requireMatch && lastAcceptedCardKey != null) {
                val stillSame = topKey != null && topKey == lastAcceptedCardKey
                if (stillSame) {
                    sameCardClearFrames = 0
                } else {
                    sameCardClearFrames += 1
                    if (sameCardClearFrames >= SAME_CARD_CLEAR_FRAMES) {
                        clearSameCardLock()
                    }
                }
            }
            val acceptAuto = autoOk && !blockedSameCard
            if (requireMatch && acceptAuto) {
                top?.imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    coil.Coil.imageLoader(app).enqueue(
                        coil.request.ImageRequest.Builder(app).data(url).build(),
                    )
                }
            }
            if (requireMatch && acceptAuto && topKey != null) {
                lastAcceptedCardKey = topKey
                sameCardClearFrames = 0
            }
            candidates.take(3).forEachIndexed { i, c ->
                com.scantrader.app.scan.ScanDebug.d(
                    "cand[$i] ${c.name} | set=${c.setCode} cn=${c.collectorNumber} " +
                        "ocr=${c.ocrScore} visual=${c.visualScore} conf=${c.matchConfidence}",
                )
            }
            com.scantrader.app.scan.ScanDebug.d(
                "gate autoOk=$autoOk strict=${gate.strict} reason=${gate.reason} " +
                    "nameOk=${gate.nameOk} nameConflict=${gate.nameConflict} " +
                    "cnOk=${gate.cnOk} setOk=${gate.setOk} countOk=${gate.countOk} " +
                    "shortCn=${gate.shortCn} conf=${gate.confidence}",
            )
            com.scantrader.app.scan.ScanDebug.d(
                "result candidates=${candidates.size} top=${top?.name} " +
                    "set=${top?.setCode} cn=${top?.collectorNumber} " +
                    "ocr=${top?.ocrScore} visual=${top?.visualScore} conf=${top?.matchConfidence} " +
                    "autoOk=$autoOk sameBlock=$blockedSameCard requireMatch=$requireMatch " +
                    "ocrMs=$ocrMs catalogMs=$catalogMs ms=${System.currentTimeMillis() - t0}",
            )
            app.scanSession.ocrText = buildString {
                append(text)
                if (topNameText.isNotBlank() && !text.contains(topNameText)) {
                    append("\n").append(topNameText)
                }
                if (bottomText.isNotBlank()) {
                    append("\n--- bottom ---\n")
                    append(bottomText)
                }
            }
            app.scanSession.parsed = matched
            app.rememberDetectedLanguage(top)
            app.scanSession.queryFingerprint = fingerprint
            app.scanSession.candidates = candidates
            app.scanSession.autoMatch = gate
            app.scanSession.autoMatchCardKey = topKey
            if (candidates.isEmpty() && text.isBlank() && bottomText.isBlank()) {
                app.scanSession.errorMessage =
                    "Nessun testo letto. Inquadra tutta la carta, soprattutto il basso a sinistra (numero + set)."
            } else if (candidates.isEmpty()) {
                app.scanSession.errorMessage =
                    "Carta non trovata. Verifica numero e set in basso a sinistra, oppure cerca manualmente."
            } else {
                app.scanSession.errorMessage = null
            }
            if (requireMatch) acceptAuto else true
        }
    }

    private fun catalogIdentity(parsed: ParsedCardText): String =
        listOf(parsed.nameGuess, parsed.collectorNumber, parsed.setCode)
            .joinToString("|") { it.orEmpty().trim().lowercase() }

    private enum class AutoHold { NONE, BUSY, UNSTABLE }

    private fun clearSameCardLock() {
        lastAcceptedCardKey = null
        sameCardClearFrames = 0
    }

    private fun autoCardKey(card: IdentifiedCard): String = card.identityKey()

    private data class HexOcrBundle(
        val top: String,
        val bl: String,
        val text: String,
        val bottomFull: String,
        val bottomRight: String,
        val bottomEdge: String,
    )

    /** Cap OCR resolution — 4K stills are ~3× slower with no accuracy gain. */
    private fun downscaleForOcr(source: android.graphics.Bitmap, maxSide: Int): android.graphics.Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide) return source
        val scale = maxSide.toFloat() / longest
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return android.graphics.Bitmap.createScaledBitmap(source, w, h, true)
    }

    companion object {
        /** Gap after an auto attempt finishes before accepting another frame. */
        private const val AUTO_COOLDOWN_MS = 280L
        private const val AUTO_OCR_MAX_SIDE = 960
        private const val MANUAL_OCR_MAX_SIDE = 1400
        /** Frames without the locked card before auto-scan may accept again. */
        private const val SAME_CARD_CLEAR_FRAMES = 3

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
    val saving = MutableStateFlow(false)
    val parsed get() = app.scanSession.parsed
    val errorMessage get() = app.scanSession.errorMessage
    val preferredCollectionId: Long? get() = app.scanSession.targetCollectionId
    val sessionLanguage: CardLanguage? get() = app.scanSession.language
    val sessionCondition: CardCondition? get() = app.scanSession.condition
    /** True when the confirm screen was reached via auto-scan hit. */
    val fromAutoScan: Boolean get() = app.scanSession.autoScan
    val autoMatch: com.scantrader.app.vision.MatchFusion.AutoMatchDecision?
        get() = app.scanSession.autoMatch
    val autoMatchCardKey: String? get() = app.scanSession.autoMatchCardKey

    init {
        viewModelScope.launch { app.repository.ensureDefaultCollection() }
    }

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

    fun setLanguage(language: CardLanguage) {
        app.scanSession.language = language
        viewModelScope.launch { app.prefs.setLanguage(language) }
    }

    suspend fun detectCardLanguage(card: IdentifiedCard): CardLanguage? =
        app.rememberDetectedLanguage(card)

    /**
     * Pokémon keeps its Cardmarket EUR. Magic uses a CardTrader average only when
     * a token is saved; otherwise, and when that average is missing, Scryfall EUR
     * (exact printing, then English same set/number, then cheapest recent print).
     */
    suspend fun proposePrice(
        card: IdentifiedCard,
        language: CardLanguage,
        condition: CardCondition,
        foil: Boolean,
    ): IdentifiedCard {
        if (card.game != Game.MTG) return card
        if (app.secrets.hasCardTraderToken()) {
            val englishId = if (card.setCode.isNotBlank() && card.collectorNumber.isNotBlank()) {
                runCatching {
                    app.scryfall.cardBySetAndNumber(card.setCode, card.collectorNumber).id
                }.getOrNull()
            } else {
                null
            }
            val market = runCatching {
                app.cardTraderPrices.quote(
                    card,
                    language,
                    condition,
                    foil,
                    listOfNotNull(englishId),
                )
            }.getOrNull()
            if (market != null) {
                return com.scantrader.app.data.catalog.ListingAverage.apply(card, market, foil)
            }
        }
        val quote = runCatching { app.scryfallEur.quote(card, foil) }.getOrNull()
        return if (quote == null) {
            card
        } else {
            com.scantrader.app.data.catalog.ListingAverage.apply(card, quote, foil)
        }
    }

    fun setCondition(condition: CardCondition) {
        app.scanSession.condition = condition
        viewModelScope.launch { app.prefs.setCondition(condition) }
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
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true
            try {
                app.repository.saveCard(collectionId, card, quantity, language, condition, foil)
                app.scanSession.targetCollectionId = collectionId
                app.scanSession.language = language
                app.scanSession.condition = condition
                app.prefs.setLanguage(language)
                app.prefs.setCondition(condition)
                onDone()
            } finally {
                saving.value = false
            }
        }
    }

    companion object {
        fun factory(app: ScanTraderApp) = factoryOf { ConfirmViewModel(app) }
    }
}

/**
 * Runs printed-language detection for manual confirm and autoscan.
 * A confident result is stored on the session so the confirm dropdown and
 * timed auto-save both start on that language. Logcat: `ScanTrader lang:`.
 */
private suspend fun ScanTraderApp.rememberDetectedLanguage(card: IdentifiedCard?): CardLanguage? {
    if (!prefs.currentDefaults().detectCardLanguage) {
        com.scantrader.app.scan.ScanDebug.d("lang: off")
        return null
    }
    val ocrName = scanSession.parsed?.nameGuess
    val ocrText = scanSession.ocrText
    val detection = runCatching {
        if (card != null) {
            languageResolver.explain(card, ocrName, ocrText)
        } else if (scanSession.game == Game.POKEMON) {
            com.scantrader.app.data.catalog.CardLanguageDetector.explain(
                ocrName = ocrName,
                englishName = null,
                printed = emptyMap(),
                ocrText = ocrText,
                pokemonSignals = true,
            )
        } else {
            com.scantrader.app.data.catalog.CardLanguageDetector.Detection(null, "none")
        }
    }.getOrElse { error ->
        com.scantrader.app.scan.ScanDebug.w("lang: failed", error)
        com.scantrader.app.data.catalog.CardLanguageDetector.Detection(null, "error")
    }
    com.scantrader.app.scan.ScanDebug.d(detection.logLine())
    detection.language?.let { scanSession.language = it }
    return detection.language
}

inline fun <reified VM : ViewModel> factoryOf(crossinline create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
