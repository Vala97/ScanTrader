@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.camera.camera2.interop.ExperimentalCamera2Interop::class,
)

package com.scantrader.app.ui.scan

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Observer
import androidx.compose.ui.res.stringResource
import com.scantrader.app.R
import com.scantrader.app.data.model.Game
import com.scantrader.app.scan.AutoScanQuick
import com.scantrader.app.scan.CameraDevices
import com.scantrader.app.scan.ManualShutter
import com.scantrader.app.scan.CardFrameGeometry
import com.scantrader.app.scan.LensCatalog
import com.scantrader.app.scan.LensKind
import com.scantrader.app.scan.ScanDebug
import com.scantrader.app.scan.ZoomFraming
import com.scantrader.app.scan.ZoomRestoreController
import com.scantrader.app.scan.ZoomRestoreStep
import com.scantrader.app.ui.ScanViewModel
import com.scantrader.app.vision.NeuralPackState
import com.scantrader.app.ui.components.GameLogo
import com.scantrader.app.ui.components.SimpleDropdown
import com.scantrader.app.ui.displayLabel
import com.scantrader.app.ui.theme.ScanColors
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

private val PanelScrim = ScanColors.scrim
private val FrameAccent = ScanColors.text
private val Gold = ScanColors.accent

@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    collectionId: Long?,
    onBack: () -> Unit,
    onCaptured: () -> Unit,
    onManualSearch: () -> Unit,
    confirmVisible: Boolean = false,
) {
    // Before the camera effects: keep the session lens and zoom, and ignore the
    // default ratio CameraX reports until that framing is applied again.
    remember { viewModel.armZoomRestore() }
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val processing by viewModel.processing.collectAsStateWithLifecycle()
    val game by viewModel.game.collectAsStateWithLifecycle()
    val defaults by viewModel.defaults.collectAsStateWithLifecycle()
    val autoScan by viewModel.autoScan.collectAsStateWithLifecycle()
    val autoStatus by viewModel.autoStatus.collectAsStateWithLifecycle()
    val mtgPack by viewModel.mtgPackState.collectAsStateWithLifecycle()
    val torchOn by viewModel.torchOn.collectAsStateWithLifecycle()
    val lightIntensity by viewModel.lightIntensity.collectAsStateWithLifecycle()
    val cameraId by viewModel.cameraId.collectAsStateWithLifecycle()
    val lensKey by viewModel.lensKey.collectAsStateWithLifecycle()
    val lensOptions by viewModel.lensOptions.collectAsStateWithLifecycle()
    val guideViewWidth by viewModel.guideViewWidth.collectAsStateWithLifecycle()
    val guideViewHeight by viewModel.guideViewHeight.collectAsStateWithLifecycle()
    var autoScanToast by remember { mutableStateOf<Toast?>(null) }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var maxTorchStrength by remember { mutableStateOf(1) }
    var torchStrengthSupported by remember { mutableStateOf(false) }
    // Collapsed by default so the card (and bottom-left set line) stays visible.
    var panelExpanded by remember { mutableStateOf(false) }
    // Restored from the ViewModel so confirm → pop does not show the game picker again.
    var gamePicked by remember { mutableStateOf(viewModel.isGameChosen) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val navigated = remember { AtomicBoolean(false) }

    LaunchedEffect(Unit) {
        viewModel.setTargetCollection(collectionId)
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(granted) {
        if (granted) viewModel.refreshHardware()
    }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(
                LifecycleCameraController.IMAGE_CAPTURE or LifecycleCameraController.IMAGE_ANALYSIS,
            )
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            // One selector instance for preview and analysis so a rebind cannot
            // negotiate a different aspect than the card guide.
            val resolution = analysisResolutionSelector()
            imageAnalysisResolutionSelector = resolution
            previewResolutionSelector = resolution
            isPinchToZoomEnabled = true
            isTapToFocusEnabled = true
            cameraSelector = CameraDevices.selectorFor(cameraId)
        }
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val zoomGate = remember { ZoomRestoreController() }
    // bindToLifecycle starts a new session at 1×. Keep the existing binding
    // when the same lens is still attached (confirm → scan).
    var cameraBound by remember { mutableStateOf(false) }
    var boundCameraId by remember { mutableStateOf<String?>(null) }
    // One PreviewView for the lifetime of this screen. Reassigning controller
    // on recomposition calls bindToLifecycle again and freezes the preview.
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // FILL_CENTER so the guide overlay matches the analysis/capture FOV
            // (FIT_CENTER letterboxes and misaligns the card-frame crop).
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            this.controller = controller
        }
    }

    fun logZoom(requested: Float, applied: Float?) {
        ScanDebug.d(
            ZoomFraming.zoomLogLine(
                requested = requested,
                applied = applied,
                lens = viewModel.lensKey.value,
                previewWidth = previewView.width,
                previewHeight = previewView.height,
            ),
        )
    }

    fun handleZoomStep(step: ZoomRestoreStep) {
        when (step) {
            is ZoomRestoreStep.Apply -> {
                val control = controller.cameraControl ?: return
                logZoom(step.requested, step.applied)
                control.setZoomRatio(step.applied)
            }
            is ZoomRestoreStep.Confirmed -> {
                logZoom(step.requested, step.applied)
                viewModel.onZoomRestoreSettled()
            }
            ZoomRestoreStep.Wait, ZoomRestoreStep.Passthrough -> Unit
        }
    }

    /** Feed one zoomState sample. No-ops until CameraControl exists. */
    fun onZoomSample(reported: Float, minZoom: Float, maxZoom: Float) {
        viewModel.updateZoomRange(minZoom, maxZoom)
        val step = zoomGate.onZoomSample(
            reported = reported,
            requested = viewModel.zoomRatio.value,
            minZoom = minZoom,
            maxZoom = maxZoom,
            cameraControlReady = controller.cameraControl != null,
        )
        handleZoomStep(step)
    }

    fun goToConfirm() {
        if (navigated.compareAndSet(false, true)) {
            persistZoom(controller, viewModel)
            viewModel.holdFraming()
            onCaptured()
        }
    }

    fun syncCameraOptics(forceZoom: Boolean = false) {
        controller.cameraInfo?.zoomState?.value?.let { state ->
            viewModel.updateZoomRange(state.minZoomRatio, state.maxZoomRatio)
        }
        applySavedZoom(
            controller,
            previewView,
            viewModel,
            viewModel.zoomRatio.value,
            force = forceZoom,
        )
        refreshTorchCapabilities(controller) { supported, max ->
            torchStrengthSupported = supported
            maxTorchStrength = max
            applyTorch(controller, torchOn, lightIntensity, max, supported)
        }
    }

    fun scheduleZoomApply(attemptsLeft: Int = 8) {
        val state = controller.cameraInfo?.zoomState?.value
        val controlReady = controller.cameraControl != null
        if (!controlReady || state == null) {
            logZoom(viewModel.zoomRatio.value, null)
            if (attemptsLeft > 0) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { scheduleZoomApply(attemptsLeft - 1) },
                    80L,
                )
            }
            return
        }
        onZoomSample(state.zoomRatio, state.minZoomRatio, state.maxZoomRatio)
    }

    LaunchedEffect(cameraId, granted, gamePicked) {
        if (!granted || !gamePicked) return@LaunchedEffect
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        val rebind = ZoomFraming.shouldRebindCamera(
            alreadyBound = cameraBound,
            boundCameraId = boundCameraId,
            requestedCameraId = cameraId,
        )
        if (!rebind) return@LaunchedEffect
        zoomGate.resetForBind()
        viewModel.armZoomRestore()
        controller.cameraSelector = CameraDevices.selectorFor(cameraId)
        controller.bindToLifecycle(lifecycleOwner)
        com.scantrader.app.scan.PortraitTargetRotation.lock(controller)
        cameraBound = true
        boundCameraId = cameraId
        controller.initializationFuture.addListener({
            com.scantrader.app.scan.PortraitTargetRotation.lock(controller)
            // Control is up. Apply only if zoomState was already observed;
            // otherwise the zoom observer applies on the first real sample.
            val state = controller.cameraInfo?.zoomState?.value
            val step = zoomGate.onCameraControlReady(
                requested = viewModel.zoomRatio.value,
                minZoom = state?.minZoomRatio ?: Float.NaN,
                maxZoom = state?.maxZoomRatio ?: Float.NaN,
            )
            handleZoomStep(step)
        }, mainExecutor)
    }

    // Zoom-only lens changes stay on the bound camera so torch and auto-scan are not reset.
    LaunchedEffect(lensKey, granted, gamePicked) {
        if (!granted || !gamePicked) return@LaunchedEffect
        val option = viewModel.currentLensOption()
        if (option?.kind != LensKind.ZOOM) return@LaunchedEffect
        if (viewModel.isFramingHeld()) return@LaunchedEffect
        syncCameraOptics(forceZoom = true)
        scheduleZoomApply()
        // Switching ratio can drop the torch on the way between lenses.
        kotlinx.coroutines.delay(250)
        if (!viewModel.isFramingHeld()) syncCameraOptics(forceZoom = true)
    }

    DisposableEffect(lifecycleOwner, granted, gamePicked, autoScan, confirmVisible) {
        if (!granted || !gamePicked) {
            return@DisposableEffect onDispose { }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (confirmVisible) {
                        viewModel.holdFraming()
                        controller.clearImageAnalysisAnalyzer()
                        return@LifecycleEventObserver
                    }
                    navigated.set(false)
                    viewModel.resumeScanning()
                    viewModel.armZoomRestore()
                    zoomGate.arm()
                    attachAutoAnalyzer(
                        controller = controller,
                        executor = cameraExecutor,
                        mainExecutor = mainExecutor,
                        enabled = autoScan,
                        viewModel = viewModel,
                        onFrame = { bitmap -> viewModel.processAutoFrame(bitmap) { goToConfirm() } },
                    )
                    val state = controller.cameraInfo?.zoomState?.value
                    if (controller.cameraControl != null && state != null) {
                        onZoomSample(state.zoomRatio, state.minZoomRatio, state.maxZoomRatio)
                    } else {
                        logZoom(viewModel.zoomRatio.value, null)
                        scheduleZoomApply()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    persistZoom(controller, viewModel)
                    viewModel.holdFraming()
                    // Drop frames while confirm is up. Clearing the analyzer does
                    // not rebind when its target resolution is unchanged.
                    controller.clearImageAnalysisAnalyzer()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            persistZoom(controller, viewModel)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    // Persist pinch-zoom continuously (debounced in ViewModel) once camera is ready.
    DisposableEffect(lifecycleOwner, granted, gamePicked, cameraId) {
        if (!granted || !gamePicked) {
            return@DisposableEffect onDispose { }
        }
        var attached: androidx.lifecycle.LiveData<androidx.camera.core.ZoomState>? = null
        val observer = Observer<androidx.camera.core.ZoomState> { state ->
            if (viewModel.isFramingHeld()) {
                onZoomSample(state.zoomRatio, state.minZoomRatio, state.maxZoomRatio)
            } else {
                viewModel.updateZoomRange(state.minZoomRatio, state.maxZoomRatio)
                viewModel.onObservedZoom(state.zoomRatio)
            }
        }
        fun attachZoomObserver() {
            val live = controller.cameraInfo?.zoomState ?: return
            if (attached === live) return
            attached?.removeObserver(observer)
            live.observe(lifecycleOwner, observer)
            attached = live
        }
        val listener = Runnable { attachZoomObserver() }
        controller.initializationFuture.addListener(listener, mainExecutor)
        attachZoomObserver()
        onDispose {
            attached?.removeObserver(observer)
        }
    }

    LaunchedEffect(torchOn, lightIntensity, granted, gamePicked, maxTorchStrength, torchStrengthSupported) {
        if (!granted || !gamePicked) return@LaunchedEffect
        applyTorch(controller, torchOn, lightIntensity, maxTorchStrength, torchStrengthSupported)
        activity?.setScanBrightness(lightIntensity.coerceIn(0.15f, 1f))
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.setScanBrightness(null)
        }
    }

    // Game pick does not resume the activity, so arm scanning once the camera is shown.
    LaunchedEffect(gamePicked) {
        if (!gamePicked) return@LaunchedEffect
        navigated.set(false)
        viewModel.resumeScanning()
    }

    // Resume auto frames once the manual shot has opened confirm (or search).
    // While that sheet is up the analyzer stays detached, then starts again on close.
    LaunchedEffect(confirmVisible) {
        if (confirmVisible) viewModel.endManualCapture()
    }

    // Confirm covers the scanner in this same composition, so the camera stays
    // bound. Stop analysis until the overlay closes; do not rebind.
    var confirmWasShown by remember { mutableStateOf(false) }
    LaunchedEffect(confirmVisible) {
        if (confirmVisible) {
            confirmWasShown = true
            persistZoom(controller, viewModel)
            viewModel.holdFraming()
            controller.clearImageAnalysisAnalyzer()
        } else if (confirmWasShown) {
            confirmWasShown = false
            navigated.set(false)
            viewModel.onZoomRestoreSettled()
            viewModel.resumeScanning()
        }
    }

    // Keys are camera/scan state only. Opening the settings panel must not
    // replace the analyzer or the resolution selector (both rebind the camera).
    LaunchedEffect(autoScan, granted, gamePicked, cameraId, confirmVisible) {
        if (!granted || !gamePicked || confirmVisible) return@LaunchedEffect
        navigated.set(false)
        attachAutoAnalyzer(
            controller = controller,
            executor = cameraExecutor,
            mainExecutor = mainExecutor,
            enabled = autoScan,
            viewModel = viewModel,
            onFrame = { bitmap -> viewModel.processAutoFrame(bitmap) { goToConfirm() } },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            runCatching { controller.enableTorch(false) }
            cameraExecutor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(ScanColors.background)) {
        if (!gamePicked) {
            GamePickOverlay(
                onPick = { picked ->
                    viewModel.chooseGame(picked)
                    gamePicked = true
                },
                onBack = onBack,
            )
            return@Box
        }

        // Fixed bounds: the settings panel is a later sibling overlay, so opening
        // it never resizes this box (a PreviewView layout change rebinds CameraX
        // and the guide would no longer match the crop).
        Box(Modifier.fillMaxSize()) {
            if (granted) {
                AndroidView(
                    factory = { _ -> previewView },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.scaleType = PreviewView.ScaleType.FILL_CENTER
                        view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        if (view.controller !== controller) view.controller = controller
                    },
                )
                CardFrameOverlay(
                    showBothBottomCorners = game == Game.POKEMON,
                    lockedWidth = guideViewWidth,
                    lockedHeight = guideViewHeight,
                    onLockViewport = viewModel::lockGuideViewport,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Serve il permesso fotocamera.", color = FrameAccent)
                }
            }
        }

        if (!confirmVisible) Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(ScanColors.topScrim, Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = FrameAccent)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Scan carta",
                        color = FrameAccent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                    )
                    Text(
                        when (game) {
                            Game.POKEMON ->
                                "Inquadra tutta la carta · n° in basso a SX (moderni) o a DX (Base/Rocket/EX–XY)"
                            else ->
                                "Inquadra tutta la carta · numero in basso"
                        },
                        color = FrameAccent.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = if (autoScan) Gold.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f),
                ) {
                    IconButton(
                        onClick = {
                            val next = !autoScan
                            viewModel.setAutoScan(next, confirmOpen = confirmVisible)
                            autoScanToast?.cancel()
                            autoScanToast = Toast.makeText(
                                context,
                                AutoScanQuick.message(next),
                                Toast.LENGTH_SHORT,
                            ).also { it.show() }
                        },
                    ) {
                        Icon(
                            Icons.Filled.CenterFocusStrong,
                            contentDescription = AutoScanQuick.contentDescription(autoScan),
                            tint = if (autoScan) Gold else FrameAccent.copy(alpha = 0.45f),
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                Surface(
                    shape = CircleShape,
                    color = if (torchOn) Gold.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f),
                ) {
                    IconButton(onClick = { viewModel.setTorchOn(!torchOn) }, enabled = granted) {
                        Icon(
                            if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = "Torcia",
                            tint = if (torchOn) Gold else FrameAccent,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            GameSegmentedControl(
                selected = game,
                onSelect = viewModel::setGame,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            if (game == Game.MTG && mtgPack is NeuralPackState.NotInstalled) {
                Text(
                    "Pack Magic non installato · riconoscimento solo con OCR",
                    color = FrameAccent.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        if (processing) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Gold)
        }

        autoStatus?.takeIf { autoScan }?.let { status ->
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(bottom = 40.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0x99000000),
            ) {
                Text(status, color = FrameAccent, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp)
            }
        }

        if (!confirmVisible) Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, PanelScrim)))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = ScanColors.panel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { panelExpanded = !panelExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Settings, null, tint = Gold, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (panelExpanded) "Nascondi impostazioni" else "Impostazioni scan",
                                color = FrameAccent,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!panelExpanded) {
                                if (autoScan) {
                                    CompactChip(
                                        icon = Icons.Filled.CenterFocusStrong,
                                        label = "Auto",
                                        active = true,
                                        onClick = { viewModel.setAutoScan(false) },
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                            }
                            Icon(
                                if (panelExpanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                contentDescription = null,
                                tint = FrameAccent,
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = panelExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column(
                            Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Illuminazione", color = FrameAccent, fontWeight = FontWeight.Medium, fontSize = 13.sp)

                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.FlashOn,
                                        null,
                                        tint = if (torchOn) Gold else FrameAccent.copy(alpha = 0.55f),
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Torcia", color = FrameAccent)
                                }
                                Switch(
                                    checked = torchOn,
                                    onCheckedChange = viewModel::setTorchOn,
                                    enabled = granted,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = ScanColors.onAccent,
                                        checkedTrackColor = Gold,
                                        uncheckedThumbColor = FrameAccent,
                                        uncheckedTrackColor = ScanColors.chipIdle,
                                    ),
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (torchOn && torchStrengthSupported) Icons.Filled.Bolt else Icons.Filled.WbSunny,
                                            null,
                                            tint = Gold,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            if (torchOn && torchStrengthSupported) "Intensità torcia" else "Luminosità schermo",
                                            color = FrameAccent,
                                            fontSize = 14.sp,
                                        )
                                    }
                                    Text(
                                        "${(lightIntensity * 100).roundToInt()}%",
                                        color = Gold,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                    )
                                }
                                Slider(
                                    value = lightIntensity,
                                    onValueChange = viewModel::setLightIntensity,
                                    valueRange = 0.15f..1f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Gold,
                                        activeTrackColor = Gold,
                                        inactiveTrackColor = FrameAccent.copy(alpha = 0.25f),
                                    ),
                                )
                            }

                            if (lensOptions.size > 1) {
                                ScanLensDropdown(
                                    options = lensOptions,
                                    lensKey = lensKey,
                                    legacyCameraId = cameraId,
                                    onSelect = viewModel::selectLens,
                                )
                            }

                            ModeToggleCard(
                                title = "Auto-scan",
                                subtitle = "Senza scatto",
                                checked = autoScan,
                                icon = Icons.Filled.CenterFocusStrong,
                                onCheckedChange = {
                                    navigated.set(false)
                                    viewModel.setAutoScan(it)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ConfidenceSlider(
                                percent = defaults.autoSaveConfidencePercent,
                                onChange = viewModel::setAutoSaveConfidencePercent,
                            )
                        }
                    }
                }
            }

            val captureManual = {
                if (granted && !processing) {
                    panelExpanded = false
                    navigated.set(false)
                    viewModel.beginManualCapture()
                    val photo = File(context.cacheDir, "scan-capture.jpg")
                    val options = ImageCapture.OutputFileOptions.Builder(photo).build()
                    controller.takePicture(
                        options,
                        cameraExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                val bitmap = com.scantrader.app.scan.CameraBitmaps.fromJpegFile(
                                    photo.absolutePath,
                                )
                                mainExecutor.execute {
                                    if (bitmap != null) {
                                        viewModel.processBitmap(bitmap) {
                                            goToConfirm()
                                        }
                                    } else {
                                        viewModel.noteManualConfirm()
                                        onManualSearch()
                                    }
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                mainExecutor.execute {
                                    viewModel.noteManualConfirm()
                                    onManualSearch()
                                }
                            }
                        },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onManualSearch) {
                    Icon(Icons.Filled.Search, null, tint = Gold)
                    Spacer(Modifier.width(6.dp))
                    Text("Cerca", color = Gold)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ManualShutter.showManualButton(autoScan)) {
                        Spacer(Modifier.size(ManualShutter.MANUAL_DIAMETER_DP.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    if (ManualShutter.showWaitingRing(autoScan)) {
                        Box(
                            modifier = Modifier
                                .size(ManualShutter.MAIN_DIAMETER_DP.dp)
                                .border(3.dp, FrameAccent.copy(alpha = 0.9f), CircleShape)
                                .semantics { contentDescription = ManualShutter.WAITING_DESCRIPTION },
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = Gold,
                                strokeWidth = 3.dp,
                            )
                        }
                    } else {
                        val shutterReady = granted && !processing
                        Box(
                            modifier = Modifier
                                .size(ManualShutter.MAIN_DIAMETER_DP.dp)
                                .clip(CircleShape)
                                .border(3.dp, FrameAccent.copy(alpha = 0.9f), CircleShape)
                                .semantics { contentDescription = ManualShutter.CONTENT_DESCRIPTION }
                                .clickable(enabled = ManualShutter.centerClickable(autoScan) && shutterReady) {
                                    captureManual()
                                }
                                .padding(6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(if (shutterReady) Gold else Gold.copy(alpha = 0.35f)),
                            )
                        }
                    }
                    if (ManualShutter.showManualButton(autoScan)) {
                        Spacer(Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .size(ManualShutter.MANUAL_DIAMETER_DP.dp)
                                .graphicsLayer { alpha = ManualShutter.manualButtonAlpha() }
                                .clip(CircleShape)
                                .background(Gold)
                                .semantics { contentDescription = ManualShutter.CONTENT_DESCRIPTION }
                                .clickable(enabled = granted) { captureManual() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.PhotoCamera,
                                contentDescription = null,
                                tint = ScanColors.onAccent,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.width(72.dp))
            }
        }
    }
}

@Composable
private fun CompactChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (active) Gold.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = if (active) Gold else FrameAccent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = if (active) Gold else FrameAccent, fontSize = 11.sp)
        }
    }
}

@Composable
private fun GameSegmentedControl(
    selected: Game,
    onSelect: (Game) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.38f))
            .horizontalScroll(androidx.compose.foundation.rememberScrollState()) // piu' giochi: la fila scorre
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Game.entries.filter { it.scannable }.forEach { option ->
            val active = option == selected
            val label = when (option) {
                Game.MTG -> "Magic"
                Game.POKEMON -> "Pokémon"
                Game.RIFTBOUND -> "Riftbound"
                else -> option.shareLabel
            }
            Row(
                Modifier
                    .widthIn(min = 72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Gold else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.White,
                ) {
                    GameLogo(
                        game = option,
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .height(16.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    color = if (active) ScanColors.onAccent else FrameAccent,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ConfidenceSlider(
    percent: Int,
    onChange: (Int) -> Unit,
) {
    val safe = percent.coerceIn(
        com.scantrader.app.data.prefs.AUTO_SAVE_CONFIDENCE_RANGE.first,
        com.scantrader.app.data.prefs.AUTO_SAVE_CONFIDENCE_RANGE.last,
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Soglia auto-salvataggio", color = FrameAccent, fontSize = 14.sp)
            Text(
                "$safe%",
                color = Gold,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
        Slider(
            value = safe.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 50f..99f,
            steps = 48,
            colors = SliderDefaults.colors(
                thumbColor = Gold,
                activeTrackColor = Gold,
                inactiveTrackColor = FrameAccent.copy(alpha = 0.25f),
            ),
        )
        Text(
            "Sotto la soglia la carta resta in conferma. Nome, set e stampa restano controllati.",
            color = FrameAccent.copy(alpha = 0.65f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ModeToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (checked) Gold.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, if (checked) Gold.copy(alpha = 0.55f) else FrameAccent.copy(alpha = 0.18f)),
    ) {
        Row(
            Modifier.clickable { onCheckedChange(!checked) }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, null, tint = if (checked) Gold else FrameAccent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(title, color = FrameAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Text(subtitle, color = FrameAccent.copy(alpha = 0.55f), fontSize = 10.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ScanColors.onAccent,
                    checkedTrackColor = Gold,
                    uncheckedThumbColor = FrameAccent,
                    uncheckedTrackColor = ScanColors.chipIdle,
                ),
            )
        }
    }
}

@Composable
private fun CardFrameOverlay(
    showBothBottomCorners: Boolean = false,
    lockedWidth: Float,
    lockedHeight: Float,
    onLockViewport: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier.onSizeChanged { size ->
            onLockViewport(size.width.toFloat(), size.height.toFloat())
        },
    ) {
        val rect = CardFrameGeometry.frameStable(
            viewWidth = size.width,
            viewHeight = size.height,
            lockedWidth = lockedWidth,
            lockedHeight = lockedHeight,
        )
        val left = rect.left
        val top = rect.top
        val right = rect.right
        val bottom = rect.bottom
        val frameW = rect.width
        val frameH = rect.height
        val corner = 36.dp.toPx()
        val stroke = 4.dp.toPx()

        drawRect(Color.Black.copy(alpha = 0.38f), size = Size(size.width, top))
        drawRect(Color.Black.copy(alpha = 0.38f), topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(Color.Black.copy(alpha = 0.38f), topLeft = Offset(0f, top), size = Size(left, frameH))
        drawRect(Color.Black.copy(alpha = 0.38f), topLeft = Offset(right, top), size = Size(size.width - right, frameH))

        fun cornerPath(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
            lineTo(x3, y3)
        }

        val accent = FrameAccent.copy(alpha = 0.95f)
        listOf(
            cornerPath(left, top + corner, left, top, left + corner, top),
            cornerPath(right - corner, top, right, top, right, top + corner),
            cornerPath(left, bottom - corner, left, bottom, left + corner, bottom),
            cornerPath(right - corner, bottom, right, bottom, right, bottom - corner),
        ).forEach { path ->
            drawPath(path, accent, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }

        drawRoundRect(
            color = accent.copy(alpha = 0.2f),
            topLeft = Offset(left, top),
            size = Size(frameW, frameH),
            cornerRadius = CornerRadius(18f, 18f),
            style = Stroke(width = 1.5.dp.toPx()),
        )

        // Hint: modern / MTG → bottom-left; Pokémon also marks bottom-right (WOTC–XY).
        val markW = frameW * 0.28f
        val markH = frameH * 0.08f
        val markY = bottom - markH - 10.dp.toPx()
        drawRoundRect(
            color = Gold.copy(alpha = 0.35f),
            topLeft = Offset(left + 8.dp.toPx(), markY),
            size = Size(markW, markH),
            cornerRadius = CornerRadius(6f, 6f),
            style = Stroke(width = 2.dp.toPx()),
        )
        if (showBothBottomCorners) {
            drawRoundRect(
                color = Gold.copy(alpha = 0.35f),
                topLeft = Offset(right - markW - 8.dp.toPx(), markY),
                size = Size(markW, markH),
                cornerRadius = CornerRadius(6f, 6f),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

private fun Activity.setScanBrightness(fraction: Float?) {
    val lp = window.attributes
    lp.screenBrightness = fraction ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    window.attributes = lp
}

@OptIn(ExperimentalCamera2Interop::class)
private fun refreshTorchCapabilities(
    controller: LifecycleCameraController,
    onResult: (supported: Boolean, max: Int) -> Unit,
) {
    val info = controller.cameraInfo ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        onResult(false, 1)
        return
    }
    val max = runCatching {
        Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)
    }.getOrNull() ?: 1
    onResult(max > 1, max.coerceAtLeast(1))
}

@OptIn(ExperimentalCamera2Interop::class)
private fun applyTorch(
    controller: LifecycleCameraController,
    enabled: Boolean,
    strengthFraction: Float,
    maxStrength: Int,
    strengthSupported: Boolean,
) {
    runCatching {
        controller.enableTorch(enabled)
        if (enabled && strengthSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val control = controller.cameraControl ?: return@runCatching
            val level = (strengthFraction * maxStrength).roundToInt().coerceIn(1, maxStrength)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.FLASH_STRENGTH_LEVEL, level)
                .build()
            Camera2CameraControl.from(control).setCaptureRequestOptions(options)
        }
    }
}

/**
 * @return the ratio sent to CameraControl, or null when bind is not finished.
 * Zoom is never written before both CameraControl and zoomState exist.
 */
private fun applySavedZoom(
    controller: LifecycleCameraController,
    previewView: PreviewView,
    viewModel: ScanViewModel,
    ratio: Float,
    force: Boolean = true,
): Float? {
    return runCatching {
        val control = controller.cameraControl ?: return null
        val state = controller.cameraInfo?.zoomState?.value ?: return null
        viewModel.updateZoomRange(state.minZoomRatio, state.maxZoomRatio)
        val clamped = ZoomFraming.clamp(ratio, state.minZoomRatio, state.maxZoomRatio) ?: return null
        viewModel.noteZoomClamped(ratio, clamped)
        ScanDebug.d(
            ZoomFraming.zoomLogLine(
                requested = ratio,
                applied = clamped,
                lens = viewModel.lensKey.value,
                previewWidth = previewView.width,
                previewHeight = previewView.height,
            ),
        )
        if (!force && abs(state.zoomRatio - clamped) < 0.02f) return clamped
        control.setZoomRatio(clamped)
        clamped
    }.getOrNull()
}

private fun attachAutoAnalyzer(
    controller: LifecycleCameraController,
    executor: java.util.concurrent.ExecutorService,
    mainExecutor: java.util.concurrent.Executor,
    enabled: Boolean,
    viewModel: ScanViewModel,
    onFrame: (android.graphics.Bitmap) -> Unit,
) {
    if (!enabled) {
        controller.clearImageAnalysisAnalyzer()
        return
    }
    controller.setImageAnalysisAnalyzer(executor) { proxy ->
        try {
            if (!viewModel.canAcceptAutoFrame()) return@setImageAnalysisAnalyzer
            val upright = com.scantrader.app.scan.CameraBitmaps.fromImageProxy(proxy)
            ScanDebug.d(
                "autoFrame rot=${upright.reportedRotation} applied=${upright.rotationDegrees} " +
                    "src=${upright.sourceSize.width}x${upright.sourceSize.height} " +
                    "bmp=${upright.bitmap.width}x${upright.bitmap.height} " +
                    "cropApplied=${upright.cropApplied}",
            )
            val bitmap = upright.bitmap
            mainExecutor.execute { onFrame(bitmap) }
        } catch (_: Throwable) {
        } finally {
            proxy.close()
        }
    }
}

private fun persistZoom(controller: LifecycleCameraController, viewModel: ScanViewModel) {
    val ratio = controller.cameraInfo?.zoomState?.value?.zoomRatio ?: return
    viewModel.persistCameraZoom(ratio)
}

@Composable
private fun GamePickOverlay(
    onPick: (Game) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(androidx.compose.foundation.rememberScrollState()) // 7 giochi: si scorre
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Indietro",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Cosa vuoi scannerizzare?",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Scegli il TCG prima di aprire la fotocamera.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        GamePickCard(Game.POKEMON, "Pokémon", onPick)
        GamePickCard(Game.MTG, "Magic: The Gathering", onPick)
        GamePickCard(Game.RIFTBOUND, "Riftbound", onPick)
        GamePickCard(Game.ONE_PIECE, "One Piece", onPick)
        GamePickCard(Game.YUGIOH, "Yu-Gi-Oh!", onPick)
        GamePickCard(Game.DRAGON_BALL, "Dragon Ball Super Fusion World", onPick)
        GamePickCard(Game.UNION_ARENA, "Union Arena", onPick)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun GamePickCard(
    game: Game,
    label: String,
    onPick: (Game) -> Unit,
) {
    Surface(
        onClick = { onPick(game) },
        shape = RoundedCornerShape(28.dp),
        color = Color.White,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GameLogo(
                game = game,
                contentDescription = label,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )
            Text(
                label,
                color = Color(0xFF0B1B33),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun ScanLensDropdown(
    options: List<com.scantrader.app.scan.LensOption>,
    lensKey: String?,
    legacyCameraId: String?,
    onSelect: (com.scantrader.app.scan.LensOption) -> Unit,
) {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val selected = LensCatalog.resolve(options, lensKey, legacyCameraId) ?: options.first()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.lens_section),
            color = FrameAccent,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
        )
        SimpleDropdown(
            label = stringResource(R.string.lens_field),
            options = options,
            selected = selected,
            onSelect = onSelect,
            labelOf = { it.displayLabel(resources) },
        )
        Text(
            stringResource(R.string.lens_hint),
            color = FrameAccent.copy(alpha = 0.65f),
            fontSize = 11.sp,
        )
    }
}

/** Analysis and preview stay at 1280×960 so a rebind cannot change the aspect. */
private fun analysisResolutionSelector(): ResolutionSelector =
    ResolutionSelector.Builder()
        .setResolutionStrategy(
            ResolutionStrategy(
                android.util.Size(ZoomFraming.ANALYSIS_WIDTH, ZoomFraming.ANALYSIS_HEIGHT),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            ),
        )
        .build()
