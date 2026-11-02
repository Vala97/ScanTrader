package com.scantrader.app.scan

/**
 * The first frame may query the catalog immediately when the title and the
 * collector are both real. A fused score at or above [SURE_CONFIDENCE], or a
 * strict match, is the same bar. A weaker score still waits for a second frame.
 */
object SureFrame {
    const val SURE_CONFIDENCE = 0.95f

    fun skipDebounce(
        name: String?,
        collector: String?,
        confidence: Float?,
        strict: Boolean,
    ): Boolean {
        if (FrameNameGate.confidentTitle(name) == null) return false
        val cn = collector?.trim().orEmpty()
        if (cn.isEmpty()) return false
        val year = cn.toIntOrNull()
        if (year != null && year in 1900..2099) return false
        if (strict) return true
        if (confidence == null) return true
        return confidence >= SURE_CONFIDENCE
    }
}
