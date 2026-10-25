package com.scantrader.app.scan

import kotlin.math.abs

/**
 * Drops auto-scan frames that are too soft or still moving.
 * The sample is a tiny luma grid so the check stays far cheaper than OCR.
 */
object FrameGate {
    const val SAMPLE_WIDTH = 24
    const val SAMPLE_HEIGHT = 32

    /** Mean absolute Laplacian below this is a blank / blurred frame. */
    const val BLUR_MIN = 8.0

    /** Mean luma change above this, inside [MOTION_WINDOW_MS], is motion. */
    const val MOTION_MAX = 35.0
    const val MOTION_WINDOW_MS = 450L

    fun blurScore(grid: IntArray, width: Int): Double {
        if (width < 3 || grid.size < width * 3) return 0.0
        val height = grid.size / width
        var sum = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val lap = 4 * grid[i] - grid[i - 1] - grid[i + 1] - grid[i - width] - grid[i + width]
                sum += abs(lap)
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    fun motionMeanAbs(previous: IntArray, current: IntArray): Double {
        if (previous.size != current.size || previous.isEmpty()) return 0.0
        var sum = 0.0
        for (i in previous.indices) sum += abs(previous[i] - current[i])
        return sum / previous.size
    }

    /** `blur`, `motion`, or null when the frame is worth OCR. */
    fun skipReason(blur: Double, motion: Double?, sincePreviousMs: Long): String? {
        if (blur < BLUR_MIN) return "blur"
        if (motion != null && sincePreviousMs in 1..MOTION_WINDOW_MS && motion > MOTION_MAX) return "motion"
        return null
    }

    fun sampleLuma(bitmap: android.graphics.Bitmap): IntArray {
        val tiny = android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            SAMPLE_WIDTH,
            SAMPLE_HEIGHT,
            true,
        )
        val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
        tiny.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
        if (tiny !== bitmap) tiny.recycle()
        return IntArray(pixels.size) { index ->
            val pixel = pixels[index]
            val r = (pixel shr 16) and 0xff
            val g = (pixel shr 8) and 0xff
            val b = pixel and 0xff
            (r * 3 + g * 6 + b) / 10
        }
    }
}
