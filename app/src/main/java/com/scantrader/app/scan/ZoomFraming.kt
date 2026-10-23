package com.scantrader.app.scan

import kotlin.math.abs

/**
 * Decides which zoom survives a camera rebind (confirm → scan).
 * The stored ratio is the user's pinch or the lens preset, whichever was last.
 * A fresh CameraX session often reports 1× before that ratio can be applied.
 */
object ZoomFraming {
    const val MATCH_EPSILON = 0.08f

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
}
