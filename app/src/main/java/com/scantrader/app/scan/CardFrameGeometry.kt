package com.scantrader.app.scan

/**
 * Shared card guide / crop geometry so the on-screen frame matches the OCR crop.
 *
 * MTG / modern Pokémon (SWSH–SV): collector + set on the **bottom-left**.
 * Vintage Pokémon (WOTC → roughly XY): collector + set symbol on the **bottom-right**.
 */
object CardFrameGeometry {
    /** Card width / height (standard TCG). */
    const val ASPECT = 63f / 88f

    /** Fraction of preview width used by the guide. */
    const val WIDTH_FRACTION = 0.82f

    /**
     * Max fraction of preview height for the frame.
     * The settings panel is an overlay and must not change this: the on-screen
     * guide and the OCR crop share one rectangle.
     */
    const val MAX_HEIGHT_FRACTION = 0.62f

    /** Shift frame slightly upward so bottom strip clears the shutter bar. */
    const val TOP_BIAS_FRACTION = 0.02f

    data class Rect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    ) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    fun frameIn(viewWidth: Float, viewHeight: Float): Rect {
        val frameW = viewWidth * WIDTH_FRACTION
        val frameH = (frameW / ASPECT).coerceAtMost(viewHeight * MAX_HEIGHT_FRACTION)
        val left = (viewWidth - frameW) / 2f
        val top = ((viewHeight - frameH) / 2f - viewHeight * TOP_BIAS_FRACTION).coerceAtLeast(viewHeight * 0.08f)
        return Rect(left, top, frameW, frameH)
    }

    fun cropBitmap(source: android.graphics.Bitmap): android.graphics.Bitmap {
        val rect = frameIn(source.width.toFloat(), source.height.toFloat())
        val x = rect.left.toInt().coerceIn(0, source.width - 1)
        val y = rect.top.toInt().coerceIn(0, source.height - 1)
        val w = rect.width.toInt().coerceAtMost(source.width - x).coerceAtLeast(1)
        val h = rect.height.toInt().coerceAtMost(source.height - y).coerceAtLeast(1)
        return android.graphics.Bitmap.createBitmap(source, x, y, w, h)
    }

    /**
     * Top strip for the card title.
     * Pokémon ~22% (title + HP). MTG ~14% (name bar only — avoid rules text).
     */
    fun topNameStrip(cardCrop: android.graphics.Bitmap, mtg: Boolean = false): android.graphics.Bitmap {
        val fraction = if (mtg) 0.14f else 0.22f
        val stripH = (cardCrop.height * fraction).toInt().coerceAtLeast(1).coerceAtMost(cardCrop.height)
        return android.graphics.Bitmap.createBitmap(cardCrop, 0, 0, cardCrop.width, stripH)
    }

    /** Bottom ~24% of the card crop — full width (both corners). */
    fun bottomStrip(cardCrop: android.graphics.Bitmap): android.graphics.Bitmap {
        val stripH = (cardCrop.height * 0.24f).toInt().coerceAtLeast(1)
        val y = (cardCrop.height - stripH).coerceAtLeast(0)
        return android.graphics.Bitmap.createBitmap(cardCrop, 0, y, cardCrop.width, stripH)
    }

    /**
     * Full-width bottom band (~15%). Older frames print `83/150` in the center
     * or beside the copyright line; modern frames use the left corner.
     */
    fun bottomBandStrip(cardCrop: android.graphics.Bitmap): android.graphics.Bitmap {
        val stripH = (cardCrop.height * 0.15f).toInt().coerceAtLeast(1).coerceAtMost(cardCrop.height)
        val y = (cardCrop.height - stripH).coerceAtLeast(0)
        return android.graphics.Bitmap.createBitmap(cardCrop, 0, y, cardCrop.width, stripH)
    }

    /**
     * Thin bottom edge (~12%) where WOTC prints `32/102 ★` inside the yellow border.
     * Avoids OCR latching onto flavor text higher up.
     */
    fun bottomEdgeStrip(cardCrop: android.graphics.Bitmap): android.graphics.Bitmap {
        val stripH = (cardCrop.height * 0.12f).toInt().coerceAtLeast(1)
        val y = (cardCrop.height - stripH).coerceAtLeast(0)
        return android.graphics.Bitmap.createBitmap(cardCrop, 0, y, cardCrop.width, stripH)
    }

    /**
     * Bottom-left identity strip (collector + set).
     * MTG uses a thinner edge + wider crop so `0181 • DFT • IT` is readable.
     */
    fun bottomLeftStrip(cardCrop: android.graphics.Bitmap, mtg: Boolean = false): android.graphics.Bitmap {
        val strip = if (mtg) bottomEdgeStrip(cardCrop) else bottomStrip(cardCrop)
        val widthFraction = if (mtg) 0.58f else 0.42f
        val w = (strip.width * widthFraction).toInt().coerceAtLeast(1)
        return android.graphics.Bitmap.createBitmap(strip, 0, 0, w, strip.height)
    }

    /**
     * Bottom-right edge — WOTC / EX–XY collector number + rarity (`32/102 ★`).
     */
    fun bottomRightStrip(cardCrop: android.graphics.Bitmap): android.graphics.Bitmap {
        val strip = bottomEdgeStrip(cardCrop)
        val w = (strip.width * 0.50f).toInt().coerceAtLeast(1)
        val x = (strip.width - w).coerceAtLeast(0)
        return android.graphics.Bitmap.createBitmap(strip, x, 0, w, strip.height)
    }
}
