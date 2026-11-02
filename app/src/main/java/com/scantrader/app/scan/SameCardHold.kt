package com.scantrader.app.scan

/**
 * While the printing just saved stays in the guide, OCR and the embedder pause.
 * A cheap luma grid says when that picture changes; the next frame then runs in full.
 */
object SameCardHold {
    /**
     * Mean absolute luma change below this is still the same picture.
     * Above [FrameGate.MOTION_MAX] a frame is already dropped as motion.
     * This sits lower so a new card resumes, and a small shake does not.
     */
    const val CHANGE_MIN = 18.0

    fun unchanged(previous: IntArray?, current: IntArray): Boolean {
        if (previous == null || previous.size != current.size || current.isEmpty()) return false
        return FrameGate.motionMeanAbs(previous, current) < CHANGE_MIN
    }
}
