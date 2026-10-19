package com.scantrader.app.scan

/**
 * Shared card guide / crop geometry so the on-screen frame matches the OCR crop.
 * MTG collector number + set code sit on the bottom-left strip — keep that inside the frame.
 */
object CardFrameGeometry {
    /** Card width / height (standard TCG). */
    const val ASPECT = 63f / 88f

    /** Fraction of preview width used by the guide. */
    const val WIDTH_FRACTION = 0.82f

    /**
     * Max fraction of preview height for the frame.
     * Keep high enough that the bottom collector line stays visible above the control bar.
     */
    const val MAX_HEIGHT_FRACTION_EXPANDED = 0.42f
    const val MAX_HEIGHT_FRACTION_COLLAPSED = 0.62f

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

    fun frameIn(viewWidth: Float, viewHeight: Float, panelExpanded: Boolean): Rect {
        val maxH = if (panelExpanded) MAX_HEIGHT_FRACTION_EXPANDED else MAX_HEIGHT_FRACTION_COLLAPSED
        val frameW = viewWidth * WIDTH_FRACTION
        val frameH = (frameW / ASPECT).coerceAtMost(viewHeight * maxH)
        val left = (viewWidth - frameW) / 2f
        val top = ((viewHeight - frameH) / 2f - viewHeight * TOP_BIAS_FRACTION).coerceAtLeast(viewHeight * 0.08f)
        return Rect(left, top, frameW, frameH)
    }

    fun cropBitmap(
        source: android.graphics.Bitmap,
        panelExpanded: Boolean = false,
    ): android.graphics.Bitmap {
        val rect = frameIn(source.width.toFloat(), source.height.toFloat(), panelExpanded)
        val x = rect.left.toInt().coerceIn(0, source.width - 1)
        val y = rect.top.toInt().coerceIn(0, source.height - 1)
        val w = rect.width.toInt().coerceAtMost(source.width - x).coerceAtLeast(1)
        val h = rect.height.toInt().coerceAtMost(source.height - y).coerceAtLeast(1)
        return android.graphics.Bitmap.createBitmap(source, x, y, w, h)
    }

    /** Bottom ~22% of the card crop — where set code + collector number live on MTG. */
    fun bottomStrip(cardCrop: android.graphics.Bitmap): android.graphics.Bitmap {
        val stripH = (cardCrop.height * 0.22f).toInt().coerceAtLeast(1)
        val y = (cardCrop.height - stripH).coerceAtLeast(0)
        return android.graphics.Bitmap.createBitmap(cardCrop, 0, y, cardCrop.width, stripH)
    }
}
