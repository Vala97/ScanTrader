package com.scantrader.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.scantrader.app.ScanTraderApp
import com.scantrader.app.data.export.CsvShare
import com.scantrader.app.data.db.CardEntryEntity
import com.scantrader.app.data.db.CollectionEntity
import com.scantrader.app.data.db.CollectionWithCount
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.ui.collection.CollectionBrowseState
import com.scantrader.app.ui.collection.CollectionBrowser
import com.scantrader.app.ui.collection.CollectionSort
import com.scantrader.app.ui.collection.browseCollection
import com.scantrader.app.ui.collection.collectionCatalogSetNames
import com.scantrader.app.ui.collection.collectionConditionChoices
import com.scantrader.app.ui.collection.collectionLanguageChoices
import com.scantrader.app.ui.collection.collectionSearchDelay
import com.scantrader.app.ui.collection.collectionSetChoices
import com.scantrader.app.ui.onboarding.Onboarding
import com.scantrader.app.data.catalog.CatalogQueryDebounce
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.data.prefs.ScanDefaults
import com.scantrader.app.data.repo.RenameCollection
import com.scantrader.app.network.scryfall.ScryfallBusyException
import com.scantrader.app.data.catalog.KnownSetCodes
import com.scantrader.app.scan.AcceptedCandidateLock
import com.scantrader.app.scan.AutosaveDuplicateGuard
import com.scantrader.app.scan.ConsecutivePrintingGuard
import com.scantrader.app.scan.BackCamera
import com.scantrader.app.scan.CameraDevices
import com.scantrader.app.scan.FrameGate
import com.scantrader.app.scan.FrameNameGate
import com.scantrader.app.scan.FrameQuery
import com.scantrader.app.scan.LensCatalog
import com.scantrader.app.scan.LensKind
import com.scantrader.app.scan.LensOption
import com.scantrader.app.scan.RejectedSetCodes
import com.scantrader.app.scan.SetPrintingGuard
import com.scantrader.app.scan.SpeculativeCatalog
import com.scantrader.app.scan.StableTopAccept
import com.scantrader.app.scan.ZoomFraming
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(private val app: ScanTraderApp) : ViewModel() {
    val collections: StateFlow<List<CollectionWithCount>> =
        app.repository.collections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val showOnboarding: StateFlow<Boolean> = app.prefs.defaults
        .map { Onboarding.shouldShow(it.onboardingCompleted, it.neuralPromptShown) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val onboardingLaunchConsumed = AtomicBoolean(false)

    init {
        viewModelScope.launch { app.repository.ensureDefaultCollection() }
        viewModelScope.launch {
            val current = app.prefs.currentDefaults()
            if (current.neuralPromptShown && !current.onboardingCompleted) {
                app.prefs.setOnboardingCompleted(true)
            }
        }
        app.pokemonCatalogs.refresh()
        app.appUpdates.checkOnLaunch()
    }

    val appUpdate = app.appUpdates.state

    fun installAppUpdate() = app.appUpdates.downloadAndInstall()

    fun dismissAppUpdate() = app.appUpdates.dismiss()

    fun openAppUpdateSettings() = app.appUpdates.openPermissionSettings()

    /** True only the first time this process should open the introduction. */
    fun consumeOnboardingLaunch(): Boolean {
        if (!showOnboarding.value) return false
        return onboardingLaunchConsumed.compareAndSet(false, true)
    }

    fun create(name: String) {
        viewModelScope.launch { app.repository.createCollection(name) }
    }

    fun rename(id: Long, name: String, onDone: (RenameCollection) -> Unit) {
        viewModelScope.launch { onDone(app.repository.renameCollection(id, name)) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { app.repository.deleteCollection(id) }
    }

    companion object {
        fun factory(app: ScanTraderApp) = factoryOf { HomeViewModel(app) }
    }
}

@OptIn(FlowPreview::class)
class CollectionViewModel(
    private val app: ScanTraderApp,
    private val collectionId: Long,
) : ViewModel() {
    val collection: StateFlow<CollectionEntity?> =
        app.repository.collection(collectionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val cards: StateFlow<List<CardEntryEntity>> =
        app.repository.cards(collectionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val exporting = MutableStateFlow(false)
    private val controls = MutableStateFlow(CollectionBrowseState())
    private val touched = AtomicBoolean(false)
    private val restored = MutableStateFlow(false)
    private val debouncedQuery = controls
        .map { it.query }
        .distinctUntilChanged()
        .debounce { collectionSearchDelay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    private val catalogSetNames = cards
        .map { list ->
            withContext(Dispatchers.IO) {
                collectionCatalogSetNames(list) { game, code ->
                    runCatching { app.localSets.cachedSetNames(game, code) }.getOrDefault(emptyList())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val browser: StateFlow<CollectionBrowser> = combine(
        cards,
        controls,
        debouncedQuery,
        catalogSetNames,
    ) { all, ctrl, query, names ->
        CollectionBrowser(
            controls = ctrl,
            result = browseCollection(all, ctrl.copy(query = query), names),
            sets = collectionSetChoices(all),
            languages = collectionLanguageChoices(all),
            conditions = collectionConditionChoices(all),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionBrowser())

    init {
        viewModelScope.launch {
            val saved = app.prefs.collectionBrowse(collectionId)
            if (!touched.get()) controls.value = saved
            restored.value = true
        }
        viewModelScope.launch {
            restored.first { it }
            controls.debounce(200).collect { app.prefs.setCollectionBrowse(collectionId, it) }
        }
    }

    fun setQuery(text: String) = edit { it.copy(query = text) }

    fun toggleSet(value: String) = edit { it.copy(sets = toggle(it.sets, value)) }

    fun toggleLanguage(value: String) = edit { it.copy(languages = toggle(it.languages, value)) }

    fun toggleCondition(value: String) = edit { it.copy(conditions = toggle(it.conditions, value)) }

    fun toggleFoil(foil: Boolean) = edit { it.copy(foil = if (it.foil == foil) null else foil) }

    fun setSort(value: CollectionSort) = edit { it.copy(sort = value) }

    fun setFiltersOpen(open: Boolean) = edit { it.copy(filtersOpen = open) }

    fun clearBrowse() = edit { CollectionBrowseState(filtersOpen = it.filtersOpen) }

    private fun edit(transform: (CollectionBrowseState) -> CollectionBrowseState) {
        touched.set(true)
        controls.value = transform(controls.value)
    }

    private fun toggle(values: Set<String>, value: String): Set<String> =
        if (value in values) values - value else values + value

    fun share(context: Context) {
        if (exporting.value || cards.value.isEmpty()) return
        val snapshot = cards.value
        val name = collection.value?.name ?: "Collezione"
        viewModelScope.launch {
            exporting.value = true
            try {
                val resolved = withContext(Dispatchers.IO) { app.blueprintNames.fill(snapshot) }
                CsvShare.share(context, name, resolved)
            } finally {
                exporting.value = false
            }
        }
    }

    fun setQuantity(entry: CardEntryEntity, quantity: Int) {
        viewModelScope.launch { app.repository.updateQuantity(entry, quantity) }
    }

    fun setCondition(entry: CardEntryEntity, condition: CardCondition) {
        viewModelScope.launch { app.repository.updateCondition(entry, condition) }
    }

    fun setLanguage(entry: CardEntryEntity, language: CardLanguage) {
        viewModelScope.launch { app.repository.updateLanguage(entry, language) }
    }

    fun setFoil(entry: CardEntryEntity, foil: Boolean) {
        viewModelScope.launch { app.repository.updateFoil(entry, foil) }
    }

    fun delete(entry: CardEntryEntity) {
        viewModelScope.launch { app.repository.deleteCard(entry) }
    }

    fun rename(name: String, onDone: (RenameCollection) -> Unit) {
        viewModelScope.launch { onDone(app.repository.renameCollection(collectionId, name)) }
    }

    fun setManualPrice(entry: CardEntryEntity, raw: String?, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onDone(app.repository.setManualPrice(entry, raw)) }
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
    val pokemonCatalogs = app.pokemonCatalogs.ui
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

    fun setShowDebugInfo(enabled: Boolean) {
        viewModelScope.launch { app.prefs.setShowDebugInfo(enabled) }
    }

    val appUpdate = app.appUpdates.state

    fun setAutoUpdateCheck(enabled: Boolean) {
        viewModelScope.launch { app.prefs.setAutoUpdateCheck(enabled) }
    }

    fun checkForAppUpdate() {
        app.appUpdates.checkNow()
    }

    fun installAppUpdate() = app.appUpdates.downloadAndInstall()

    fun dismissAppUpdate() = app.appUpdates.dismiss()

    fun openAppUpdateSettings() = app.appUpdates.openPermissionSettings()

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

    fun hasManualPokemonCatalog(): Boolean = app.neuralPack.hasManualPokemonCatalog()

    fun manualPokemonCardCount(): Int? = app.neuralPack.manualPokemonCardCount()

    fun importPokemonPack(uri: android.net.Uri) {
        viewModelScope.launch {
            app.neuralPack.importPokemonCatalog(uri)
        }
    }

    fun loadNeuralPack() {
        viewModelScope.launch { app.neuralPack.ensureLoaded() }
    }

    fun refreshPokemonCatalogs() {
        app.pokemonCatalogs.refresh()
    }

    fun downloadPokemonCatalog(id: String) {
        app.pokemonCatalogs.download(id)
    }

    fun deletePokemonCatalog(id: String) {
        app.pokemonCatalogs.delete(id)
    }

    companion object {
        fun factory(app: ScanTraderApp) = factoryOf { SettingsViewModel(app) }
    }
}

class ScanViewModel(private val app: ScanTraderApp) : ViewModel() {
    val processing = MutableStateFlow(false)
    val game = MutableStateFlow(app.scanSession.game)
    val defaults: StateFlow<ScanDefaults> =
        app.prefs.defaults.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ScanDefaults(CardLanguage.ENGLISH, CardCondition.NEAR_MINT),
        )
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

    /**
     * Identity of the card on the confirm overlay. Released when that overlay
     * closes and when the parsed name or collector changes.
     */
    private val acceptedLock = AcceptedCandidateLock(clearFramesNeeded = SAME_CARD_CLEAR_FRAMES)
    private val duplicateGuard = AutosaveDuplicateGuard()
    private val consecutiveGuard = ConsecutivePrintingGuard()

    /** Set when this frame's match is the printing we just accepted. */
    @Volatile
    private var repeatPrinting: Boolean = false

    private val lastAcceptedCardKey: String?
        get() = acceptedLock.key

    private val autoBusy = AtomicBoolean(false)
    private val autoHitPending = AtomicBoolean(false)
    private val catalogDebounce = CatalogQueryDebounce()
    private val rejectedSets = RejectedSetCodes()
    private val stableTop = StableTopAccept()
    /** Ultimo codice con piu' versioni gia' aperto in conferma dall'autoscan (non si riapre finche' non cambia carta). */
    private var lastVariantKey: String? = null
    private val speculativeCatalog = SpeculativeCatalog()
    private val speculativeInFlight = AtomicBoolean(false)

    @Volatile
    private var speculativeDeferred: Deferred<List<IdentifiedCard>>? = null

    private var lastLuma: IntArray? = null
    private var lastLumaAtMs: Long = 0L
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

    fun setAutoSaveConfidencePercent(percent: Int) {
        viewModelScope.launch { app.prefs.setAutoSaveConfidencePercent(percent) }
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
        val closed = acceptedLock.snapshot
        duplicateGuard.onOverlayClosed(closed?.key, closed?.name)
        acceptedLock.clear()
        cancelSpeculative()
        catalogDebounce.reset()
        rejectedSets.clear()
        stableTop.reset()
        if (autoScan.value) {
            autoStatus.value = if (duplicateGuard.holdingSameCard()) {
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
        // The catalog job outlives the frame that started it, so the next OCR can overlap it.
        if (speculativeInFlight.get()) return true
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
                repeatPrinting = false
                val matched = analyze(bitmap, requireMatch = true)
                if (matched) {
                    autoHitPending.set(true)
                    autoStatus.value = "Carta trovata"
                    onHit()
                } else when (autoHold) {
                    AutoHold.BUSY -> autoStatus.value = "Scryfall occupato, riprovo…"
                    AutoHold.SPECULATIVE -> autoStatus.value = "Analisi in corso…"
                    AutoHold.UNSTABLE -> autoStatus.value = if (repeatPrinting || duplicateGuard.holdingSameCard() || lastAcceptedCardKey != null) {
                        "Metti un’altra carta…"
                    } else {
                        "Inquadra la carta…"
                    }
                    AutoHold.NONE -> autoStatus.value = if (repeatPrinting || duplicateGuard.holdingSameCard() || lastAcceptedCardKey != null) {
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
            val oriented = com.scantrader.app.scan.CameraBitmaps.ensurePortrait(bitmap)
            if (oriented.width != bitmap.width || oriented.height != bitmap.height) {
                com.scantrader.app.scan.ScanDebug.d(
                    "portrait ${bitmap.width}x${bitmap.height} -> ${oriented.width}x${oriented.height}",
                )
            }
            var croppedFull = com.scantrader.app.scan.CardFrameGeometry.cropBitmap(oriented)
            if (autoMode && skipUnstableFrame(croppedFull, t0)) {
                return@withContext false
            }
            val isMtg = game.value == Game.MTG
            val isPokemon = game.value == Game.POKEMON
            // One Piece, Dragon Ball, Yu-Gi-Oh!, Union Arena: l'angolo del codice a piena risoluzione,
            // letto al posto della fascia in basso a destra dei Pokémon vecchi
            val ctCorner = com.scantrader.app.scan.CardFrameGeometry.ctCodeStrip(croppedFull, game.value)
            val readRight = isPokemon || ctCorner != null
            // Bottom strips stay at 480. The title strip keeps enough pixels for a long name.
            fun scaled(full: android.graphics.Bitmap): Pair<android.graphics.Bitmap, android.graphics.Bitmap> {
                val bottomCap = if (autoMode) AUTO_STRIP_MAX_SIDE else MANUAL_OCR_MAX_SIDE
                val titleCap = if (!autoMode) {
                    bottomCap
                } else {
                    com.scantrader.app.scan.CardOrientation.titleMaxSide(
                        width = full.width,
                        height = full.height,
                        floor = AUTO_STRIP_MAX_SIDE,
                        ceiling = AUTO_TITLE_MAX_SIDE,
                        titleHeightFraction = if (isMtg) 0.14f else 0.22f,
                        minTitlePx = 96,
                    )
                }
                val bottom = downscaleForOcr(full, bottomCap)
                val title = if (titleCap == bottomCap) bottom else downscaleForOcr(full, titleCap)
                return bottom to title
            }
            var (cropped, titleCard) = scaled(croppedFull)
            com.scantrader.app.scan.ScanDebug.d(
                "crop ${cropped.width}x${cropped.height} title ${titleCard.width}x${titleCard.height} " +
                    "from ${oriented.width}x${oriented.height}" +
                    if (cropped !== croppedFull || titleCard !== croppedFull) " (downscaled)" else "",
            )
            val tOcr0 = System.currentTimeMillis()
            // Parallel OCR: title + bottom corners. Full card only when the number is still missing.
            val (topNameText, bottomLeft, text, bottomFull, bottomRight, bottomEdge) =
                coroutineScope {
                    // Auto: skip 2.5× enhance on first pass; manual keeps enhance for tiny type.
                    val enhanceFirst = !autoMode
                    val topJob = async {
                        runCatching {
                            app.ocr.recognize(
                                com.scantrader.app.scan.CardFrameGeometry.topNameStrip(titleCard, mtg = isMtg),
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
                    // Vintage Pokémon prints the collector on the bottom-right. Read it with the title.
                    val brJob = async {
                        if (ctCorner != null) {
                            runCatching { app.ocr.recognize(ctCorner, enhance = true) }.getOrDefault("")
                        } else if (!readRight || (!autoMode && isPokemon)) {
                            ""
                        } else {
                            runCatching {
                                app.ocr.recognize(
                                    com.scantrader.app.scan.CardFrameGeometry.bottomRightStrip(cropped),
                                )
                            }.getOrDefault("")
                        }
                    }
                    var top = topJob.await()
                    var bl = blJob.await()
                    var br = brJob.await()
                    var edge = ""
                    fun bottomSoFar(): String =
                        listOf(edge, br, bl).filter { it.isNotBlank() }.joinToString("\n")
                    var quick = com.scantrader.app.scan.OcrParser.parse(
                        rawText = top,
                        game = game.value,
                        bottomPriorityText = bottomSoFar(),
                        namePriorityText = top,
                    )
                    // Auto: enhanced corners only while the collector is still missing.
                    if (autoMode && quick.collectorNumber.isNullOrBlank()) {
                        val eblJob = async {
                            runCatching {
                                val strip = if (isMtg) {
                                    com.scantrader.app.scan.CardFrameGeometry.bottomBandStrip(cropped)
                                } else {
                                    com.scantrader.app.scan.CardFrameGeometry.bottomLeftStrip(cropped)
                                }
                                app.ocr.recognize(strip, enhance = true)
                            }.getOrDefault(bl)
                        }
                        val ebrJob = async {
                            if (!readRight || ctCorner != null) {
                                "" // l'angolo del codice e' gia' letto ingrandito
                            } else {
                                runCatching {
                                    app.ocr.recognize(
                                        com.scantrader.app.scan.CardFrameGeometry.bottomRightStrip(cropped),
                                        enhance = true,
                                    )
                                }.getOrDefault(br)
                            }
                        }
                        val edgeJob = async {
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
                        val enhancedBl = eblJob.await()
                        val enhancedBr = ebrJob.await()
                        val enhancedEdge = edgeJob.await()
                        if (enhancedBl.isNotBlank()) bl = enhancedBl
                        if (enhancedBr.isNotBlank()) br = enhancedBr
                        if (enhancedEdge.isNotBlank()) edge = enhancedEdge
                        quick = com.scantrader.app.scan.OcrParser.parse(
                            rawText = top,
                            game = game.value,
                            bottomPriorityText = bottomSoFar(),
                            namePriorityText = top,
                        )
                    }
                    // Collector line in the title band: rotation put the card's bottom at the top.
                    if (com.scantrader.app.scan.CardOrientation.regionMappingOff(top, quick.nameGuess)) {
                        com.scantrader.app.scan.ScanDebug.d("OCR region map off, rotate 180 and reread")
                        val flippedFull = com.scantrader.app.scan.CameraBitmaps.rotateDegrees(
                            croppedFull,
                            180,
                            recycleSource = false,
                        )
                        val (flippedBottom, flippedTitle) = scaled(flippedFull)
                        val newTop = runCatching {
                            app.ocr.recognize(
                                com.scantrader.app.scan.CardFrameGeometry.topNameStrip(
                                    flippedTitle,
                                    mtg = isMtg,
                                ),
                                enhance = enhanceFirst,
                            )
                        }.getOrDefault("")
                        val newBl = runCatching {
                            val strip = if (isMtg) {
                                com.scantrader.app.scan.CardFrameGeometry.bottomBandStrip(flippedBottom)
                            } else {
                                com.scantrader.app.scan.CardFrameGeometry.bottomLeftStrip(flippedBottom)
                            }
                            app.ocr.recognize(strip, enhance = enhanceFirst)
                        }.getOrDefault("")
                        val retry = com.scantrader.app.scan.OcrParser.parse(
                            rawText = newTop,
                            game = game.value,
                            bottomPriorityText = newBl,
                            namePriorityText = newTop,
                        )
                        if (com.scantrader.app.scan.CardOrientation.rotatedPassWins(
                                quick.nameGuess,
                                retry.nameGuess,
                            )
                        ) {
                            croppedFull = flippedFull
                            cropped = flippedBottom
                            titleCard = flippedTitle
                            if (newTop.isNotBlank()) top = newTop
                            if (newBl.isNotBlank()) bl = newBl
                            quick = retry
                            com.scantrader.app.scan.ScanDebug.d(
                                "OCR region map kept rot180 name=${retry.nameGuess}",
                            )
                        }
                    }
                    val stripText = listOf(top, bl, br, edge).filter { it.isNotBlank() }.joinToString("\n")
                    val trainerLayout = isPokemon &&
                        com.scantrader.app.scan.OcrParser.looksLikeTrainerLayout(stripText)
                    val weakTitle = !quick.nameGuess.isNullOrBlank() &&
                        com.scantrader.app.scan.CardOrientation.implausibleTitle(quick.nameGuess)
                    val mappingOff = com.scantrader.app.scan.CardOrientation.regionMappingOff(
                        top,
                        quick.nameGuess,
                    )
                    val identityReady = !weakTitle && !mappingOff &&
                        com.scantrader.app.scan.OcrParser.identityReadyForFastPath(
                            quick,
                            stripText,
                        )
                    val quickId = catalogIdentity(quick)
                    val nameAndCnStable = autoMode && !weakTitle && !mappingOff &&
                        !quick.nameGuess.isNullOrBlank() &&
                        !quick.collectorNumber.isNullOrBlank() &&
                        catalogDebounce.seenThisBefore(quickId)
                    val stripsEnough = autoMode && !weakTitle && !mappingOff &&
                        !quick.collectorNumber.isNullOrBlank() &&
                        (
                            !quick.nameGuess.isNullOrBlank() ||
                                quick.setCardCount != null ||
                                !quick.setCode.isNullOrBlank()
                            )
                    if (autoMode && top.isBlank() && bl.isBlank() && br.isBlank() && edge.isBlank()) {
                        com.scantrader.app.scan.ScanDebug.d("OCR early-abort empty strips")
                        HexOcrBundle(top, bl, "", "", br, edge)
                    } else if (trainerLayout && autoMode) {
                        // Rules text is read after the match so it does not sit on the critical path.
                        val titleJob = async {
                            runCatching {
                                app.ocr.recognize(
                                    com.scantrader.app.scan.CardFrameGeometry.trainerTitleStrip(cropped),
                                    enhance = true,
                                )
                            }.getOrDefault(top)
                        }
                        val bottomJob = async {
                            runCatching {
                                app.ocr.recognize(
                                    com.scantrader.app.scan.CardFrameGeometry.trainerBottomLeftStrip(cropped),
                                    enhance = true,
                                )
                            }.getOrDefault(bl)
                        }
                        val titleText = titleJob.await().ifBlank { top }
                        val bottomText = bottomJob.await().ifBlank { bl }
                        com.scantrader.app.scan.ScanDebug.d("OCR trainer layout title+bottom (rules deferred)")
                        HexOcrBundle(
                            top = titleText,
                            bl = bottomText,
                            text = titleText,
                            bottomFull = bottomText,
                            bottomRight = br,
                            bottomEdge = edge,
                        )
                    } else if (trainerLayout) {
                        val titleJob = async {
                            runCatching {
                                app.ocr.recognize(
                                    com.scantrader.app.scan.CardFrameGeometry.trainerTitleStrip(cropped),
                                    enhance = true,
                                )
                            }.getOrDefault(top)
                        }
                        val rulesJob = async {
                            runCatching {
                                app.ocr.recognize(
                                    com.scantrader.app.scan.CardFrameGeometry.trainerRulesStrip(cropped),
                                )
                            }.getOrDefault("")
                        }
                        val bottomJob = async {
                            runCatching {
                                app.ocr.recognize(
                                    com.scantrader.app.scan.CardFrameGeometry.trainerBottomLeftStrip(cropped),
                                    enhance = true,
                                )
                            }.getOrDefault(bl)
                        }
                        val titleText = titleJob.await().ifBlank { top }
                        val rulesText = rulesJob.await()
                        val bottomText = bottomJob.await().ifBlank { bl }
                        com.scantrader.app.scan.ScanDebug.d("OCR trainer layout title+rules+bottom")
                        HexOcrBundle(
                            top = titleText,
                            bl = bottomText,
                            text = listOf(titleText, rulesText).filter { it.isNotBlank() }.joinToString("\n"),
                            bottomFull = bottomText,
                            bottomRight = br,
                            bottomEdge = edge,
                        )
                    } else if (identityReady || nameAndCnStable || stripsEnough) {
                        com.scantrader.app.scan.ScanDebug.d(
                            when {
                                identityReady -> "OCR fast-path identity ready"
                                nameAndCnStable -> "OCR skip extra passes (name+cn stable)"
                                else -> "OCR strips only"
                            },
                        )
                        HexOcrBundle(top, bl, top, bl, br, edge)
                    } else {
                        val fullBitmap = if (autoMode) {
                            downscaleForOcr(croppedFull, AUTO_FULL_MAX_SIDE)
                        } else {
                            cropped
                        }
                        val fullJob = async {
                            runCatching {
                                // Auto Pokémon: latin-only full pass; CJK only on manual.
                                app.ocr.recognize(fullBitmap, cjk = isPokemon && !autoMode)
                            }.getOrDefault("")
                        }
                        val bfJob = async {
                            runCatching {
                                app.ocr.recognize(
                                    com.scantrader.app.scan.CardFrameGeometry.bottomStrip(fullBitmap),
                                )
                            }.getOrDefault("")
                        }
                        val fullBrJob = async {
                            if (!readRight || br.isNotBlank() || ctCorner != null) {
                                br
                            } else {
                                runCatching {
                                    app.ocr.recognize(
                                        com.scantrader.app.scan.CardFrameGeometry.bottomRightStrip(fullBitmap),
                                        enhance = true,
                                    )
                                }.getOrDefault("")
                            }
                        }
                        val beJob = async {
                            if (!isPokemon || edge.isNotBlank()) {
                                edge
                            } else {
                                runCatching {
                                    app.ocr.recognize(
                                        com.scantrader.app.scan.CardFrameGeometry.bottomEdgeStrip(fullBitmap),
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
                            bottomRight = fullBrJob.await(),
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
            var parsed = com.scantrader.app.scan.OcrParser.parse(
                rawText = text,
                game = game.value,
                bottomPriorityText = bottomText,
                namePriorityText = topNameText,
            )
            com.scantrader.app.scan.ScanDebug.d(
                "OCR fullChars=${text.length} topChars=${topNameText.length} " +
                    "bottomChars=${bottomText.length} bl=${bottomLeft.length} br=${bottomRight.length} " +
                    "parsed name=${parsed.nameGuess} cn=${parsed.collectorNumber} " +
                    "set=${parsed.setCode} count=${parsed.setCardCount} ocrMs=$ocrMs",
            )
            val knownSet = KnownSetCodes.scrub(game.value, parsed)
            if (knownSet.setCode != parsed.setCode) {
                com.scantrader.app.scan.ScanDebug.d(
                    "set discarded (unknown set code) set=${parsed.setCode}",
                )
                parsed = knownSet
            }
            if (game.value == Game.MTG) {
                val scrubbed = rejectedSets.scrub(parsed)
                if (scrubbed.setCode != parsed.setCode) {
                    com.scantrader.app.scan.ScanDebug.d(
                        "set discarded (not a printing of ${parsed.nameGuess}) set=${parsed.setCode}",
                    )
                    parsed = scrubbed
                }
            }
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
                logTiming("empty", ocrMs, 0, System.currentTimeMillis() - t0)
                if (acceptedLock.key != null) return@withContext false
                app.scanSession.resetResults()
                app.scanSession.game = game.value
                app.scanSession.errorMessage =
                    "Nessun testo letto. Inquadra tutta la carta, soprattutto il basso a sinistra (numero + set)."
                return@withContext false
            }

            val lockedKey = if (autoMode) acceptedLock.key else null
            if (autoMode && lockedKey != null && acceptedLock.shouldIgnore(parsed)) {
                com.scantrader.app.scan.ScanDebug.d(
                    "frame ignored (candidate locked key=${acceptedLock.key}) " +
                        "name=${parsed.nameGuess} cn=${parsed.collectorNumber}",
                )
                return@withContext false
            }
            if (autoMode && lockedKey != null && acceptedLock.key == null) {
                cancelSpeculativeJob()
                speculativeCatalog.clear()
                catalogDebounce.reset()
                com.scantrader.app.scan.ScanDebug.d(
                    "candidate lock released name=${parsed.nameGuess} cn=${parsed.collectorNumber}",
                )
            }
            if (autoMode) duplicateGuard.observe(parsed)
            val debounceFrame = com.scantrader.app.data.catalog.CatalogQueryDebounce.Frame(
                name = parsed.nameGuess,
                collectorNumber = parsed.collectorNumber,
                setCode = parsed.setCode,
                setCardCount = parsed.setCardCount,
                pokemon = isPokemon,
            )
            val speculativeStep = if (autoMode && isPokemon) speculativeCatalog.onOcr(parsed) else null
            if (speculativeStep?.dropInFlight == true && speculativeStep.start != true) {
                cancelSpeculativeJob()
                com.scantrader.app.scan.ScanDebug.d(
                    "speculative dropped (name=${parsed.nameGuess} cn=${parsed.collectorNumber})",
                )
            }
            if (speculativeStep?.start == true) {
                catalogDebounce.shouldQuery(debounceFrame)
                startSpeculative(parsed)
                autoHold = AutoHold.SPECULATIVE
                app.scanSession.parsed = parsed
                logTiming("speculative-start", ocrMs, 0, System.currentTimeMillis() - t0)
                return@withContext false
            }
            app.scanSession.resetResults()
            app.scanSession.game = game.value

            val idReady = com.scantrader.app.scan.OcrParser.identityReadyForFastPath(parsed)
            val cooling = autoMode && app.scryfall.coolingDown()
            if (cooling) {
                catalogDebounce.invalidateLastQuery()
                com.scantrader.app.scan.ScanDebug.d("Scryfall cooldown")
            }
            val tCat0 = System.currentTimeMillis()
            val prefetched = if (speculativeStep?.accept == true) {
                val hits = takeSpeculative().orEmpty()
                val agreed = FrameNameGate.keep(hits, parsed)
                if (hits.isNotEmpty() && agreed.isEmpty()) {
                    com.scantrader.app.scan.ScanDebug.d(
                        "speculative dropped (name disagrees frame=${parsed.nameGuess} " +
                            "top=${hits.firstOrNull()?.name})",
                    )
                    null
                } else {
                    agreed.takeIf { it.isNotEmpty() }
                }
            } else {
                null
            }
            val debounceWants = when {
                !autoMode -> true
                cooling -> false
                else -> catalogDebounce.shouldQuery(debounceFrame)
            }
            // Prefetched hits already are the lookup. An empty prefetch still falls through once.
            val queryCatalog = prefetched == null &&
                (debounceWants || speculativeStep?.accept == true)
            // Majority name/cn/set over the recent frames. One FASE / asch read must not
            // be what we send to TCGdex, and it must not reset the stability clock.
            val voted = if (autoMode && !cooling) catalogDebounce.voted(parsed) else parsed
            val matched = if (autoMode) FrameQuery.resolve(parsed, voted) else voted
            val catalogIdentity = catalogIdentity(matched)
            val neuralOn = app.neuralPack.isInstalled(game.value)
            // No catalog yet and no neural pack: wait for a second identical id.
            // With a pack installed, the gallery still runs so a visual hit can autosave.
            if (autoMode && !queryCatalog && prefetched == null && !neuralOn) {
                autoHold = if (cooling) AutoHold.BUSY else AutoHold.UNSTABLE
                app.scanSession.parsed = matched
                com.scantrader.app.scan.ScanDebug.d(
                    "catalog skipped (OCR not stable yet) id=$catalogIdentity",
                )
                logTiming("catalog-skipped", ocrMs, 0, System.currentTimeMillis() - t0)
                return@withContext false
            }
            if (autoMode && !queryCatalog && prefetched == null) {
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

            val fetched = if (prefetched != null) {
                prefetched to emptyList<IdentifiedCard>()
            } else try {
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
                        if (game.value == Game.RIFTBOUND || game.value in app.ctGames) app.scanSession.errorMessage = t.message
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
                        Game.RIFTBOUND, Game.ONE_PIECE, Game.DRAGON_BALL, Game.YUGIOH, Game.UNION_ARENA, Game.OTHER -> emptyList<IdentifiedCard>() // nessun pack visivo
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
            } else if (fingerprint != null && catalogHits.isNotEmpty() &&
                game.value != Game.RIFTBOUND && game.value !in app.ctGames
            ) {
                // giochi CardTrader: le versioni (normale, Pre-release, promo) hanno quasi la stessa arte,
                // l'ordine resta quello del catalogo (prima il numero esatto)
                runCatching { app.visualRanker.rank(fingerprint, catalogHits) }
                    .onFailure { com.scantrader.app.scan.ScanDebug.w("visualRanker failed", it) }
                    .getOrDefault(catalogHits)
            } else {
                catalogHits
            }
            val merged = com.scantrader.app.vision.mergeHybridCandidates(fingerprintRanked, galleryHits)
            val guarded = if (game.value == Game.MTG) {
                rejectedSets.note(matched, merged)
                val cleaned = SetPrintingGuard.discardUnknownSet(matched, merged)
                if (cleaned.setCode != matched.setCode) {
                    com.scantrader.app.scan.ScanDebug.d(
                        "set discarded (not a printing of ${matched.nameGuess}) set=${matched.setCode}",
                    )
                }
                cleaned
            } else {
                matched
            }
            val forScore = if (guarded.setCode != matched.setCode) {
                merged.map { it.copy(ocrScore = null) }
            } else {
                merged
            }
            val fused = com.scantrader.app.vision.MatchFusion.fuse(forScore, guarded)
                .distinctBy {
                    it.scryfallId
                        ?: it.tcgdexId
                        ?: "${it.setCode}-${it.collectorNumber}-${it.name}"
                }
                .take(12)
            val candidates = FrameNameGate.keep(fused, parsed)
            if (candidates.size < fused.size) {
                com.scantrader.app.scan.ScanDebug.d(
                    "result dropped (name disagrees frame=${parsed.nameGuess} " +
                        "top=${fused.firstOrNull()?.name})",
                )
            }
            val top = candidates.firstOrNull()
            val gateParsed = if (FrameNameGate.confidentTitle(parsed.nameGuess) != null) {
                guarded.copy(nameGuess = parsed.nameGuess)
            } else {
                guarded
            }
            // Score a dropped id hit so a name mismatch is nameConflict, not noTop.
            val gateSubject = top ?: fused.firstOrNull()
            var gate = com.scantrader.app.vision.MatchFusion.evaluateAutoMatch(
                gateSubject,
                gateParsed,
                candidates,
            )
            if (requireMatch && top != null && !gate.accepted && stableTop.ready(top, gate)) {
                com.scantrader.app.scan.ScanDebug.d(
                    "stable top accept set=${top.setCode} cn=${top.collectorNumber}",
                )
                gate = gate.copy(accepted = true, reason = "stableTop")
            } else if (top == null || gate.nameConflict || !gate.nameOk) {
                stableTop.reset()
            }
            val autoOk = gate.accepted && top != null
            val ocrTitle = matched.nameGuess ?: parsed.nameGuess
            val resolved = top?.let {
                com.scantrader.app.data.catalog.CardLanguageDetector.resolveCopyLanguage(it, ocrTitle)
            }
            val copyTop = resolved?.card
            if (resolved != null && resolved.detection.evidence.startsWith("title=")) {
                com.scantrader.app.scan.ScanDebug.d(
                    "${resolved.detection.logLine()} printed=${copyTop?.printedLang} " +
                        "catalog=${copyTop?.catalogLang}",
                )
            }
            val topKey = copyTop?.let { autoCardKey(it) }
            val blockedSameCard = requireMatch &&
                autoOk &&
                topKey != null &&
                topKey == acceptedLock.key
            val duplicateBlocked = requireMatch &&
                autoOk &&
                topKey != null &&
                !duplicateGuard.allowAutoSave(topKey)
            val repeatCard = if (copyTop != null && requireMatch && autoOk && consecutiveGuard.sameAsLast(copyTop)) {
                copyTop
            } else {
                null
            }
            val consecutiveBlocked = repeatCard != null
            val acceptAuto = autoOk && !blockedSameCard && !duplicateBlocked && !consecutiveBlocked
            // One Piece, Dragon Ball, Yu-Gi-Oh!, Union Arena: codice letto ma piu' versioni con lo stesso codice.
            // L'autoscan apre la conferma per scegliere (senza salvataggio automatico), una volta per codice.
            val readCode = matched.collectorNumber?.takeIf { it.isNotBlank() }?.let { "${matched.setCode}|$it" }
            if (readCode != null && readCode != lastVariantKey) lastVariantKey = null
            val variantKey = readCode.takeIf {
                requireMatch && !autoOk && game.value in app.ctGames && gate.cnOk && candidates.size > 1
            }
            val chooseVersion = variantKey != null && variantKey != lastVariantKey && !duplicateBlocked && !consecutiveBlocked
            if (chooseVersion) {
                lastVariantKey = variantKey
                com.scantrader.app.scan.ScanDebug.d("ct variants: conferma per scegliere la versione code=$variantKey n=${candidates.size}")
            }
            if (duplicateBlocked) {
                com.scantrader.app.scan.ScanDebug.d(
                    "autosave: skipped (same card still in frame key=$topKey)",
                )
            }
            if (repeatCard != null) {
                repeatPrinting = true
                com.scantrader.app.scan.ScanDebug.d(
                    "autosave: skipped (same printing as last accepted " +
                        "set=${repeatCard.setCode} cn=${repeatCard.collectorNumber} " +
                        "lang=${repeatCard.printedLang ?: repeatCard.catalogLang} " +
                        "finish=${repeatCard.finish ?: "standard"})",
                )
            }
            if (requireMatch && acceptAuto) {
                copyTop?.let { acceptedCard ->
                    acceptedLock.lock(
                        key = autoCardKey(acceptedCard),
                        collectorNumber = acceptedCard.collectorNumber,
                        name = FrameNameGate.confidentTitle(parsed.nameGuess)
                            ?: matched.nameGuess
                            ?: acceptedCard.printedName
                            ?: acceptedCard.name,
                    )
                    consecutiveGuard.noteAccepted(acceptedCard)
                }
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
            logTiming(
                stage = if (requireMatch && acceptAuto) "match" else "result",
                ocrMs = ocrMs,
                catalogMs = catalogMs,
                totalMs = System.currentTimeMillis() - t0,
            )
            if (requireMatch && (duplicateBlocked || consecutiveBlocked || (candidates.isEmpty() && fused.isNotEmpty()))) {
                autoHold = AutoHold.UNSTABLE
                // Do not publish this hit. resetResults already dropped the previous
                // card, so the confirm screen cannot keep showing it as the new one.
                return@withContext false
            }
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
            app.scanSession.queryFingerprint = fingerprint
            app.scanSession.candidates = if (copyTop != null && copyTop != top) {
                listOf(copyTop) + candidates.drop(1)
            } else {
                candidates
            }
            app.scanSession.autoMatch = gate
            app.scanSession.autoMatchCardKey = topKey
            if (candidates.isEmpty() && text.isBlank() && bottomText.isBlank()) {
                app.scanSession.errorMessage =
                    "Nessun testo letto. Inquadra tutta la carta, soprattutto il basso a sinistra (numero + set)."
            } else if (candidates.isEmpty() && game.value == Game.RIFTBOUND && app.riftbound.lastError != null) {
                app.scanSession.errorMessage = app.riftbound.lastError
            } else if (candidates.isEmpty() && app.ctGames[game.value]?.lastError != null) {
                app.scanSession.errorMessage = app.ctGames[game.value]?.lastError
            } else if (candidates.isEmpty()) {
                app.scanSession.errorMessage =
                    "Carta non trovata. Verifica numero e set in basso a sinistra, oppure cerca manualmente."
            } else {
                app.scanSession.errorMessage = null
            }
            if (requireMatch && acceptAuto && isPokemon) {
                scheduleRulesOcr(croppedFull)
            }
            if (requireMatch) acceptAuto || chooseVersion else true
        }
    }

    private fun catalogIdentity(parsed: ParsedCardText): String =
        listOf(parsed.nameGuess, parsed.collectorNumber, parsed.setCode)
            .joinToString("|") { it.orEmpty().trim().lowercase() }

    private enum class AutoHold { NONE, BUSY, UNSTABLE, SPECULATIVE }

    private fun clearSameCardLock() {
        acceptedLock.clear()
        duplicateGuard.clear()
        consecutiveGuard.clear()
        cancelSpeculative()
        catalogDebounce.reset()
        rejectedSets.clear()
        stableTop.reset()
    }

    private fun skipUnstableFrame(bitmap: android.graphics.Bitmap, startedAt: Long): Boolean {
        val luma = runCatching { FrameGate.sampleLuma(bitmap) }.getOrNull() ?: return false
        val now = System.currentTimeMillis()
        val previous = lastLuma
        val since = if (lastLumaAtMs == 0L) 0L else now - lastLumaAtMs
        val motion = if (previous != null && previous.size == luma.size) {
            FrameGate.motionMeanAbs(previous, luma)
        } else {
            null
        }
        val blur = FrameGate.blurScore(luma, FrameGate.SAMPLE_WIDTH)
        lastLuma = luma
        lastLumaAtMs = now
        val reason = FrameGate.skipReason(blur, motion, since) ?: return false
        if (reason == "motion") duplicateGuard.noteSceneChanged()
        autoHold = AutoHold.UNSTABLE
        logTiming("skip-$reason", 0, 0, now - startedAt)
        return true
    }

    private fun startSpeculative(parsed: ParsedCardText) {
        val previous = speculativeDeferred
        val gameNow = game.value
        val deferred = viewModelScope.async(Dispatchers.IO) {
            try {
                app.catalog.identify(gameNow, parsed)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                com.scantrader.app.scan.ScanDebug.e("speculative identify failed", error)
                emptyList()
            }
        }
        speculativeDeferred = deferred
        speculativeInFlight.set(true)
        deferred.invokeOnCompletion {
            if (speculativeDeferred === deferred) speculativeInFlight.set(false)
        }
        previous?.cancel()
    }

    private suspend fun takeSpeculative(): List<IdentifiedCard>? {
        val deferred = speculativeDeferred ?: return null
        speculativeDeferred = null
        speculativeInFlight.set(false)
        return try {
            deferred.await()
        } catch (cancel: CancellationException) {
            if (!coroutineContext.isActive) throw cancel
            null
        }
    }

    private fun cancelSpeculativeJob() {
        speculativeDeferred?.cancel()
        speculativeDeferred = null
        speculativeInFlight.set(false)
    }

    private fun cancelSpeculative() {
        speculativeCatalog.clear()
        cancelSpeculativeJob()
        lastLuma = null
        lastLumaAtMs = 0L
    }

    /** Attack / rules text for language keywords. Runs after the match is already shown. */
    private fun scheduleRulesOcr(cardCrop: android.graphics.Bitmap) {
        val source = downscaleForOcr(cardCrop, AUTO_FULL_MAX_SIDE)
        val strip = com.scantrader.app.scan.CardFrameGeometry.trainerRulesStrip(source)
        val gen = ++app.scanSession.ocrGeneration
        app.scanSession.rulesPending = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rules = runCatching { app.ocr.recognize(strip) }.getOrDefault("")
                if (app.scanSession.ocrGeneration == gen && rules.isNotBlank()) {
                    val existing = app.scanSession.ocrText
                    if (!existing.contains(rules)) {
                        app.scanSession.ocrText = existing + "\n" + rules
                    }
                }
            } finally {
                if (app.scanSession.ocrGeneration == gen) {
                    app.scanSession.rulesPending = false
                }
            }
        }
    }

    private fun logTiming(stage: String, ocrMs: Long, catalogMs: Long, totalMs: Long) {
        com.scantrader.app.scan.ScanDebug.d(
            "timing: stage=$stage ocrMs=$ocrMs catalogMs=$catalogMs totalMs=$totalMs",
        )
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
        /** Collector strips. The title strip uses [AUTO_TITLE_MAX_SIDE] so long names survive. */
        private const val AUTO_STRIP_MAX_SIDE = 480
        /** Title band. 480px on the whole card was dropping "Down, Down to Goblin-town". */
        private const val AUTO_TITLE_MAX_SIDE = 960
        /** Full-card fallback and the deferred rules strip. */
        private const val AUTO_FULL_MAX_SIDE = 640
        private const val MANUAL_OCR_MAX_SIDE = 1400
        /** Frames without the locked card before auto-scan may accept again. */
        private const val SAME_CARD_CLEAR_FRAMES = 3

        fun factory(app: ScanTraderApp) = factoryOf { ScanViewModel(app) }
    }
}

class ConfirmViewModel(private val app: ScanTraderApp) : ViewModel() {
    val collections: StateFlow<List<CollectionEntity>> =
        app.repository.collectionList()
            .map { list -> list.filter { it.id != app.tracker.mirrorId } } // non si salvano scansioni nella collezione "Cardtrader"
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val defaults: StateFlow<ScanDefaults> =
        app.prefs.defaults.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ScanDefaults(CardLanguage.ENGLISH, CardCondition.NEAR_MINT),
        )
    val candidates: StateFlow<List<IdentifiedCard>> = app.scanSession.candidatesFlow
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
        app.scanSession.candidates = app.scanSession.candidates.toList()
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
                app.scanSession.candidates = fused
            } catch (t: Throwable) {
                app.scanSession.candidates = emptyList()
                if (app.scanSession.game == Game.RIFTBOUND) app.scanSession.errorMessage = t.message
            } finally {
                searching.value = false
            }
        }
    }

    fun setLanguage(language: CardLanguage) {
        app.scanSession.language = language
        viewModelScope.launch { app.prefs.setLanguage(language) }
    }

    suspend fun detectCardLanguage(card: IdentifiedCard): CardLanguage? {
        // Yu-Gi-Oh!: la lingua e' nel codice stampato (POTD-IT003)
        if (card.game == Game.YUGIOH) app.scanSession.parsed?.printedLang?.let { CardLanguage.fromCatalogCode(it) }?.let { return it }
        val started = System.currentTimeMillis()
        while (app.scanSession.rulesPending && System.currentTimeMillis() - started < RULES_WAIT_MS) {
            delay(40)
        }
        return app.rememberDetectedLanguage(card)
    }

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
        if (card.game == Game.RIFTBOUND || card.ctBlueprintId != null) {
            val market = runCatching { app.cardTraderPrices.quote(card, language, condition, foil) }.getOrNull()
            return if (market != null) com.scantrader.app.data.catalog.ListingAverage.apply(card, market, foil) else card
        }
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
                val rowId = app.repository.saveCard(collectionId, card, quantity, language, condition, foil)
                app.tracker.cardSaved(card, quantity, language, condition, foil, rowId) // al DLS Tracker, poi Cardtrader
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
        private const val RULES_WAIT_MS = 800L

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
    val detection = try {
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
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        com.scantrader.app.scan.ScanDebug.w("lang: failed", error)
        com.scantrader.app.data.catalog.CardLanguageDetector.Detection(null, "error")
    }
    val published = com.scantrader.app.data.catalog.CardLanguageDetector.retainConfident(
        scanSession.language,
        detection,
    )
    if (published.language != null && detection.language == null) {
        val evidence = scanSession.languageEvidence?.takeIf { it.isNotBlank() } ?: "none"
        com.scantrader.app.scan.ScanDebug.d(
            "lang: ${published.language.label} evidence=kept previous=$evidence",
        )
        return published.language
    }
    com.scantrader.app.scan.ScanDebug.d(published.logLine())
    if (detection.language != null) {
        scanSession.language = detection.language
        scanSession.languageEvidence = detection.evidence
    }
    return detection.language
}

inline fun <reified VM : ViewModel> factoryOf(crossinline create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
