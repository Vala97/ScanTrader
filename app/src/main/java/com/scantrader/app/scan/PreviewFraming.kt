package com.scantrader.app.scan

import android.util.Rational
import android.view.Surface
import android.view.View
import androidx.camera.core.ViewPort
import java.util.Locale
import kotlin.math.abs

/**
 * When the scanner may bind, and the portrait viewport shared by preview,
 * analysis, and capture. [androidx.camera.view.LifecycleCameraController] is
 * final and has no public viewport setter. Attaching it to
 * [androidx.camera.view.PreviewView] is the supported way to put
 * [androidx.camera.view.PreviewView.getViewPort] on the CameraX UseCaseGroup.
 * A framing failure still binds the same use cases.
 */
object PreviewFraming {
    private const val SIZE_SLOP_PX = 2

    fun readyToBind(width: Int, height: Int): Boolean = width > 1 && height > 1

    /**
     * False while the keyboard is open or the view has not been measured.
     * A new size replaces the previous one; the first measurement is not kept
     * for the rest of the process.
     */
    fun guideSize(
        measuredWidth: Float,
        measuredHeight: Float,
        keyboardOpen: Boolean,
    ): Pair<Float, Float>? {
        if (keyboardOpen) return null
        if (measuredWidth <= 1f || measuredHeight <= 1f) return null
        return measuredWidth to measuredHeight
    }

    /** True when this measurement repeats the previous one and the keyboard is closed. */
    fun acceptGuideMeasurement(
        width: Float,
        height: Float,
        previousWidth: Float,
        previousHeight: Float,
        keyboardOpen: Boolean,
    ): Boolean {
        val size = guideSize(width, height, keyboardOpen) ?: return false
        if (previousWidth <= 1f || previousHeight <= 1f) return false
        return abs(size.first - previousWidth) <= 1f && abs(size.second - previousHeight) <= 1f
    }

    fun shouldBind(
        granted: Boolean,
        gamePicked: Boolean,
        keyboardOpen: Boolean,
        width: Int,
        height: Int,
        alreadyBound: Boolean,
        boundCameraId: String?,
        requestedCameraId: String?,
        boundWidth: Int,
        boundHeight: Int,
    ): Boolean {
        if (!granted || !gamePicked || keyboardOpen) return false
        if (!readyToBind(width, height)) return false
        if (!alreadyBound) return true
        if (boundCameraId != requestedCameraId) return true
        return abs(width - boundWidth) > SIZE_SLOP_PX || abs(height - boundHeight) > SIZE_SLOP_PX
    }

    /** Portrait [ViewPort] with the view's aspect. Null until the view has a real size. */
    fun sharedViewPort(width: Int, height: Int, layoutDirection: Int): ViewPort? {
        if (!readyToBind(width, height)) return null
        val direction = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
        return ViewPort.Builder(Rational(width, height), Surface.ROTATION_0)
            .setScaleType(ViewPort.FILL_CENTER)
            .setLayoutDirection(direction)
            .build()
    }

    /** Preview, analysis, and capture. Analysis is never optional. */
    fun bindPlan(viewportReady: Boolean): BindPlan = BindPlan(
        useCases = listOf(BindUseCase.PREVIEW, BindUseCase.ANALYSIS, BindUseCase.CAPTURE),
        withViewport = viewportReady,
    )

    /** Framing setup failed. The bind list stays the same, without a viewport. */
    fun bindPlanAfterFailure(): BindPlan = bindPlan(viewportReady = false)

    fun frameLogLine(
        viewWidth: Int,
        viewHeight: Int,
        previewRotation: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        zoom: Float?,
        guideLeft: Float,
        guideTop: Float,
        guideWidth: Float,
        guideHeight: Float,
    ): String {
        val zoomText = if (zoom == null || !zoom.isFinite() || zoom <= 0f) {
            "pending"
        } else {
            String.format(Locale.US, "%.2f", zoom)
        }
        val guideText = if (guideWidth <= 0f || guideHeight <= 0f) {
            "none"
        } else {
            String.format(
                Locale.US,
                "[%.0f,%.0f %.0fx%.0f]",
                guideLeft,
                guideTop,
                guideWidth,
                guideHeight,
            )
        }
        return "frame: view=${viewWidth}x${viewHeight} rotation=$previewRotation " +
            "crop=[$cropLeft,$cropTop,$cropRight,$cropBottom] zoom=$zoomText guide=$guideText"
    }
}

enum class BindUseCase { PREVIEW, ANALYSIS, CAPTURE }

data class BindPlan(
    val useCases: List<BindUseCase>,
    val withViewport: Boolean,
)
