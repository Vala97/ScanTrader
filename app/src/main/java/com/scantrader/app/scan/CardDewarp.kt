package com.scantrader.app.scan

import android.graphics.Bitmap
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Perspective-straightens the four card corners into a tight card bitmap.
 * Milo's catalogs are full-card scans. Pixels outside the quad (table, fingers)
 * are not sampled. There is no square fallback.
 *
 * Corners are TL, TR, BR, BL as eight floats (x, y).
 */
object CardDewarp {
    data class Image(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    )

    fun outputSize(corners: FloatArray): Pair<Int, Int> {
        require(corners.size == 8)
        val width = ((edge(corners, 0, 1) + edge(corners, 3, 2)) / 2f)
            .roundToInt()
            .coerceIn(2, MAX_SIDE)
        val height = ((edge(corners, 0, 3) + edge(corners, 1, 2)) / 2f)
            .roundToInt()
            .coerceIn(2, MAX_SIDE)
        return width to height
    }

    /**
     * Maps each destination pixel back onto [corners] and bilinear-samples [source].
     */
    fun warp(source: Image, corners: FloatArray): Image {
        val (dstW, dstH) = outputSize(corners)
        val homography = destToSource(corners, dstW, dstH)
        val out = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val (sx, sy) = map(homography, x.toDouble(), y.toDouble())
                out[y * dstW + x] = sample(source.pixels, source.width, source.height, sx, sy)
            }
        }
        return Image(dstW, dstH, out)
    }

    /**
     * Part of the source the guide quad needs: its bounding box, scaled down so
     * the long side is at most [maxSide]. A full-size still (3072×4080) is never
     * copied whole into the heap; an analysis frame keeps scale 1.
     */
    data class SourceRegion(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        /** Region pixels per source pixel (≤ 1). */
        val scale: Float,
    ) {
        val scaledWidth: Int get() = (width * scale).roundToInt().coerceAtLeast(2)
        val scaledHeight: Int get() = (height * scale).roundToInt().coerceAtLeast(2)

        /** [corners] moved into a region image of [imageWidth]×[imageHeight]. */
        fun mapCorners(
            corners: FloatArray,
            imageWidth: Int = scaledWidth,
            imageHeight: Int = scaledHeight,
        ): FloatArray {
            val sx = imageWidth.toFloat() / width
            val sy = imageHeight.toFloat() / height
            return FloatArray(corners.size) { i ->
                if (i % 2 == 0) (corners[i] - left) * sx else (corners[i] - top) * sy
            }
        }
    }

    fun sourceRegion(corners: FloatArray, sourceWidth: Int, sourceHeight: Int, maxSide: Int = MAX_SIDE): SourceRegion {
        require(corners.size == 8)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until 4) {
            minX = minOf(minX, corners[i * 2])
            maxX = maxOf(maxX, corners[i * 2])
            minY = minOf(minY, corners[i * 2 + 1])
            maxY = maxOf(maxY, corners[i * 2 + 1])
        }
        val left = kotlin.math.floor(minX).toInt().coerceIn(0, sourceWidth - 1)
        val top = kotlin.math.floor(minY).toInt().coerceIn(0, sourceHeight - 1)
        // +1: bilinear sampling at the far corner also reads the next pixel.
        val right = (kotlin.math.ceil(maxX).toInt() + 1).coerceIn(left + 1, sourceWidth)
        val bottom = (kotlin.math.ceil(maxY).toInt() + 1).coerceIn(top + 1, sourceHeight)
        val width = right - left
        val height = bottom - top
        val longest = maxOf(width, height)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        return SourceRegion(left, top, width, height, scale)
    }

    /**
     * Guide quad on [source], or null when the preview size is unknown.
     * Does not fall back to [CardFrameGeometry.cropBitmap].
     */
    fun cardFromGuide(
        source: Bitmap,
        viewWidth: Float,
        viewHeight: Float,
        rotate180: Boolean = false,
    ): Bitmap? {
        val corners = CardFrameGeometry.guideCorners(
            source.width,
            source.height,
            viewWidth,
            viewHeight,
        ) ?: return null
        val region = sourceRegion(corners, source.width, source.height)
        val image = if (region.scale < 1f) {
            val scaled = Bitmap.createBitmap(
                source,
                region.left,
                region.top,
                region.width,
                region.height,
                android.graphics.Matrix().apply {
                    setScale(
                        region.scaledWidth.toFloat() / region.width,
                        region.scaledHeight.toFloat() / region.height,
                    )
                },
                true,
            )
            try {
                val pixels = IntArray(scaled.width * scaled.height)
                scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                Image(scaled.width, scaled.height, pixels)
            } finally {
                if (scaled !== source) scaled.recycle()
            }
        } else {
            val pixels = IntArray(region.width * region.height)
            source.getPixels(pixels, 0, region.width, region.left, region.top, region.width, region.height)
            Image(region.width, region.height, pixels)
        }
        val warped = warp(image, region.mapCorners(corners, image.width, image.height))
        var bitmap = Bitmap.createBitmap(
            warped.pixels,
            warped.width,
            warped.height,
            Bitmap.Config.ARGB_8888,
        )
        if (rotate180) {
            val rotated = CameraBitmaps.rotateDegrees(bitmap, 180)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        return bitmap
    }

    internal fun destToSource(corners: FloatArray, dstW: Int, dstH: Int): DoubleArray {
        val dw = (dstW - 1).toFloat()
        val dh = (dstH - 1).toFloat()
        val dest = floatArrayOf(
            0f, 0f,
            dw, 0f,
            dw, dh,
            0f, dh,
        )
        return homography(dest, corners)
    }

    internal fun map(h: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
        val w = h[6] * x + h[7] * y + h[8]
        val u = (h[0] * x + h[1] * y + h[2]) / w
        val v = (h[3] * x + h[4] * y + h[5]) / w
        return u to v
    }

    private fun edge(corners: FloatArray, from: Int, to: Int): Float {
        val dx = corners[to * 2] - corners[from * 2]
        val dy = corners[to * 2 + 1] - corners[from * 2 + 1]
        return hypot(dx, dy)
    }

    /** 3×3 row-major, last element 1. Maps [from] points onto [to] points. */
    private fun homography(from: FloatArray, to: FloatArray): DoubleArray {
        val a = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)
        for (i in 0 until 4) {
            val x = from[i * 2].toDouble()
            val y = from[i * 2 + 1].toDouble()
            val u = to[i * 2].toDouble()
            val v = to[i * 2 + 1].toDouble()
            val r0 = i * 2
            val r1 = r0 + 1
            a[r0][0] = x
            a[r0][1] = y
            a[r0][2] = 1.0
            a[r0][6] = -u * x
            a[r0][7] = -u * y
            b[r0] = u
            a[r1][3] = x
            a[r1][4] = y
            a[r1][5] = 1.0
            a[r1][6] = -v * x
            a[r1][7] = -v * y
            b[r1] = v
        }
        val h = solve(a, b)
        return doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
    }

    private fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
        val n = 8
        val m = Array(n) { row ->
            DoubleArray(n + 1) { col ->
                if (col < n) matrix[row][col] else rhs[row]
            }
        }
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (kotlin.math.abs(m[row][col]) > kotlin.math.abs(m[pivot][col])) pivot = row
            }
            val swap = m[col]
            m[col] = m[pivot]
            m[pivot] = swap
            val div = m[col][col]
            require(kotlin.math.abs(div) > 1e-8) { "card corners are degenerate" }
            for (c in col..n) m[col][c] /= div
            for (row in 0 until n) {
                if (row == col) continue
                val factor = m[row][col]
                if (factor == 0.0) continue
                for (c in col..n) m[row][c] -= factor * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] }
    }

    private fun sample(pixels: IntArray, width: Int, height: Int, x: Double, y: Double): Int {
        val x0 = kotlin.math.floor(x).toInt().coerceIn(0, width - 1)
        val y0 = kotlin.math.floor(y).toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = (x - x0).toFloat().coerceIn(0f, 1f)
        val fy = (y - y0).toFloat().coerceIn(0f, 1f)
        return blend(
            blend(pixels[y0 * width + x0], pixels[y0 * width + x1], fx),
            blend(pixels[y1 * width + x0], pixels[y1 * width + x1], fx),
            fy,
        )
    }

    private fun blend(left: Int, right: Int, t: Float): Int {
        fun ch(value: Int, shift: Int): Int = (value shr shift) and 0xff
        fun mix(shift: Int): Int {
            val a = ch(left, shift)
            val b = ch(right, shift)
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /**
     * Longest side the straightened card may have. Milo embeds at 448 px, so
     * 900 keeps detail while a full-size still stays a few MB on the heap.
     */
    const val MAX_SIDE = 900
}
