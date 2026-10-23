package com.scantrader.app.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Perceptual fingerprints for hybrid reverse-image ranking.
 * dHash (64-bit) + compact RGB histogram — fast on-device, no AGPL models.
 */
object ImageFingerprint {
    data class Result(
        val artHash: Long,
        val fullHash: Long,
        /** 64-bin RGB histogram, L2-normalised. */
        val artHistogram: FloatArray,
    )

    fun fromCardCrop(cardCrop: Bitmap): Result {
        val art = artRegion(cardCrop)
        return try {
            Result(
                artHash = dHash(art),
                fullHash = dHash(cardCrop),
                artHistogram = rgbHistogram(art),
            )
        } finally {
            if (art !== cardCrop) art.recycle()
        }
    }

    fun fromReference(bitmap: Bitmap): Result = fromCardCrop(bitmap)

    /**
     * Illustration window: skip thin borders and name strip, keep art before text box.
     * Works for classic MTG frames and most Pokémon layouts.
     */
    fun artRegion(card: Bitmap): Bitmap {
        val w = card.width
        val h = card.height
        val insetX = (w * 0.07f).toInt().coerceAtLeast(1)
        val top = (h * 0.09f).toInt().coerceAtLeast(0)
        val bottom = (h * 0.58f).toInt().coerceAtMost(h)
        val width = (w - 2 * insetX).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        return Bitmap.createBitmap(card, insetX, top, width, height)
    }

    fun dHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L
        var bit = 0
        try {
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val left = luminance(scaled.getPixel(x, y))
                    val right = luminance(scaled.getPixel(x + 1, y))
                    if (left > right) {
                        hash = hash or (1L shl bit)
                    }
                    bit++
                }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        return hash
    }

    fun hammingSimilarity(a: Long, b: Long): Float {
        val distance = java.lang.Long.bitCount(a xor b)
        return 1f - distance / 64f
    }

    fun histogramSimilarity(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        var dot = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
        }
        return dot.coerceIn(0f, 1f)
    }

    /**
     * Combined visual score in 0..1 using art hash, full-card hash, and art histogram.
     */
    fun similarity(query: Result, reference: Result): Float {
        val art = hammingSimilarity(query.artHash, reference.artHash)
        val full = hammingSimilarity(query.fullHash, reference.fullHash)
        val hist = histogramSimilarity(query.artHistogram, reference.artHistogram)
        val hashBest = maxOf(art, full * 0.92f)
        return (0.72f * hashBest + 0.28f * hist).coerceIn(0f, 1f)
    }

    private fun rgbHistogram(bitmap: Bitmap): FloatArray {
        val bins = FloatArray(64)
        val scaled = Bitmap.createScaledBitmap(bitmap, 32, 44, true)
        try {
            val w = scaled.width
            val h = scaled.height
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            for (pixel in pixels) {
                val r = Color.red(pixel) ushr 6
                val g = Color.green(pixel) ushr 6
                val b = Color.blue(pixel) ushr 6
                bins[(r shl 4) or (g shl 2) or b] += 1f
            }
            var norm = 0f
            for (v in bins) norm += v * v
            val inv = if (norm > 0f) 1f / sqrt(norm) else 0f
            for (i in bins.indices) bins[i] *= inv
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        return bins
    }

    private fun luminance(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (r * 30 + g * 59 + b * 11) / 100
    }
}
