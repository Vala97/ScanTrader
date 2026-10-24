package com.scantrader.app.scan

import java.util.Locale
import kotlin.math.abs

/**
 * Decides which zoom survives a camera rebind (confirm → scan).
 * The stored ratio is the user's pinch or the lens preset, whichever was last.
 * A fresh CameraX session often reports 1× before that ratio can be applied.
 *
 * Analysis and preview stay on one 4:3 size so a rebind cannot pick a new aspect.
 */
object ZoomFraming {
    const val MATCH_EPSILON = 0.08f

    /** Fixed ImageAnalysis / Preview target. Do not derive this from the view. */
    const val ANALYSIS_WIDTH = 1280
    const val ANALYSIS_HEIGHT = 960

    fun matches(reported: Float, target: Float): Boolean =
        reported.isFinite() && target.isFinite() && abs(reported - target) <= MATCH_EPSILON

    /** Null when [ratio] is not a usable zoom. Clamped once the camera range is known. */
    fun clamp(ratio: Float, min: Float, max: Float): Float? {
        if (!ratio.isFinite() || ratio <= 0f) return null
        if (!min.isFinite() || !max.isFinite() || min <= 0f || max < min) return ratio
        return ratio.coerceIn(min, max)
    }

    /**
     * True while a restore is in progress and the camera has not reached the
     * stored framing yet. Those reports must not be saved.
     */
    fun ignoreReportedZoom(stored: Float, reported: Float, restoreArmed: Boolean): Boolean {
        if (!restoreArmed) return false
        if (!reported.isFinite() || reported <= 0f) return true
        return !matches(reported, stored)
    }

    /**
     * Unbind often jumps back to ~1×. That jump is not a pinch.
     * A stored framing already near 1× is left to the caller.
     */
    fun isUnbindReset(stored: Float, reported: Float): Boolean {
        if (!stored.isFinite() || !reported.isFinite()) return false
        if (abs(stored - 1f) <= 0.15f) return false
        return abs(reported - 1f) <= 0.05f && abs(reported - stored) > 0.15f
    }

    /**
     * A reported ratio may be written into the session only when it is a real
     * pinch. While a restore is pending, only the stored ratio counts.
     * The 1× value CameraX emits on unbind never counts.
     */
    fun acceptReportedZoom(stored: Float, reported: Float, restorePending: Boolean): Boolean {
        if (!reported.isFinite() || reported <= 0f) return false
        if (restorePending) return matches(reported, stored)
        if (isUnbindReset(stored, reported)) return false
        return true
    }

    /**
     * True when the scan screen must call bindToLifecycle again.
     * Returning from confirm with the same lens keeps the existing binding.
     */
    fun shouldRebindCamera(
        alreadyBound: Boolean,
        boundCameraId: String?,
        requestedCameraId: String?,
    ): Boolean {
        if (!alreadyBound) return true
        return boundCameraId != requestedCameraId
    }

    /** Logcat line. Tag is [ScanDebug.TAG], so the filter is `ScanTrader zoom:`. */
    fun zoomLogLine(
        requested: Float,
        applied: Float?,
        lens: String?,
        previewWidth: Int,
        previewHeight: Int,
    ): String {
        val appliedText = if (applied == null || !applied.isFinite() || applied <= 0f) {
            "pending"
        } else {
            formatRatio(applied)
        }
        val lensText = lens?.takeIf { it.isNotBlank() } ?: "none"
        return "zoom: requested=${formatRatio(requested)} applied=$appliedText " +
            "lens=$lensText previewSize=${previewWidth}x${previewHeight}"
    }

    private fun formatRatio(value: Float): String =
        String.format(Locale.US, "%.2f", value)
}

/**
 * Applies the session zoom only after CameraControl exists and zoomState has
 * been delivered. Samples from before that pair are ignored, including the
 * default 1× a new session reports.
 */
class ZoomRestoreController {
    private var cameraControlReady: Boolean = false
    private var zoomStateObserved: Boolean = false
    private var applySent: Boolean = false
    private var settled: Boolean = false
    private var lastApplied: Float? = null
    private var lastReported: Float? = null

    val isReady: Boolean get() = cameraControlReady && zoomStateObserved

    /** Drop bind progress. The next session must be observed again. */
    fun resetForBind() {
        cameraControlReady = false
        zoomStateObserved = false
        applySent = false
        settled = false
        lastApplied = null
        lastReported = null
    }

    /**
     * Another visit to the scanner. A camera that stayed bound keeps
     * [cameraControlReady]; the next zoom sample is still required.
     */
    fun arm() {
        applySent = false
        settled = false
        lastApplied = null
        zoomStateObserved = false
        lastReported = null
    }

    fun onCameraControlReady(
        requested: Float,
        minZoom: Float,
        maxZoom: Float,
    ): ZoomRestoreStep {
        cameraControlReady = true
        val reported = lastReported
        if (!zoomStateObserved || reported == null) return ZoomRestoreStep.Wait
        // This sample was seen before control was ready. Re-apply, but do not
        // treat a matching value as proof the new session has settled.
        return evaluate(reported, requested, minZoom, maxZoom, allowConfirm = false)
    }

    fun onZoomSample(
        reported: Float,
        requested: Float,
        minZoom: Float,
        maxZoom: Float,
        cameraControlReady: Boolean,
    ): ZoomRestoreStep {
        if (reported.isFinite() && reported > 0f) lastReported = reported
        zoomStateObserved = true
        // The flag tracks this sample. A report from before CameraControl exists
        // must not be treated as a finished bind.
        this.cameraControlReady = cameraControlReady
        if (!cameraControlReady) return ZoomRestoreStep.Wait
        return evaluate(reported, requested, minZoom, maxZoom, allowConfirm = true)
    }

    private fun evaluate(
        reported: Float,
        requested: Float,
        minZoom: Float,
        maxZoom: Float,
        allowConfirm: Boolean,
    ): ZoomRestoreStep {
        if (settled) return ZoomRestoreStep.Passthrough
        val clamped = ZoomFraming.clamp(requested, minZoom, maxZoom) ?: return ZoomRestoreStep.Wait
        val pending = lastApplied
        if (applySent && pending != null && ZoomFraming.matches(clamped, pending)) {
            if (ZoomFraming.matches(reported, clamped)) {
                if (!allowConfirm) {
                    return ZoomRestoreStep.Apply(requested = requested, applied = clamped)
                }
                settled = true
                return ZoomRestoreStep.Confirmed(requested = requested, applied = clamped)
            }
            if (ZoomFraming.isUnbindReset(clamped, reported)) {
                return ZoomRestoreStep.Apply(requested = requested, applied = clamped)
            }
            return ZoomRestoreStep.Wait
        }
        applySent = true
        lastApplied = clamped
        if (allowConfirm && ZoomFraming.matches(reported, clamped)) {
            settled = true
            return ZoomRestoreStep.Confirmed(requested = requested, applied = clamped)
        }
        return ZoomRestoreStep.Apply(requested = requested, applied = clamped)
    }
}

sealed class ZoomRestoreStep {
    data object Wait : ZoomRestoreStep()
    data class Apply(val requested: Float, val applied: Float) : ZoomRestoreStep()
    data class Confirmed(val requested: Float, val applied: Float) : ZoomRestoreStep()
    data object Passthrough : ZoomRestoreStep()
}
