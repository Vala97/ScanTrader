package com.scantrader.app.scan

import kotlin.math.abs
import kotlin.math.min

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

    /**
     * Guide for the scanner overlay.
     *
     * [lockedWidth] / [lockedHeight] are the first measured view. A later layout
     * pass — or a preview buffer with a different aspect — must not recompute
     * the rectangle from that new box. The locked frame is mapped uniformly
     * into the current view so the card aspect stays put.
     *
     * Preview stream width and height are intentionally not parameters.
     */
    fun frameStable(
        viewWidth: Float,
        viewHeight: Float,
        lockedWidth: Float,
        lockedHeight: Float,
    ): Rect {
        if (viewWidth <= 0f || viewHeight <= 0f) return Rect(0f, 0f, 0f, 0f)
        if (lockedWidth <= 1f || lockedHeight <= 1f) return frameIn(viewWidth, viewHeight)
        val base = frameIn(lockedWidth, lockedHeight)
        if (abs(viewWidth - lockedWidth) < 1f && abs(viewHeight - lockedHeight) < 1f) return base
        val scale = min(viewWidth / lockedWidth, viewHeight / lockedHeight)
        val mappedW = base.width * scale
        val mappedH = base.height * scale
        val left = (viewWidth - mappedW) / 2f
        val top = base.top * scale + (viewHeight - lockedHeight * scale) / 2f
        return Rect(left, top, mappedW, mappedH)
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
     * Share of the title-bar width kept for the name.
     * MTG mana symbols and the Pokémon HP/PS box sit on the right.
     */
    const val MTG_TITLE_WIDTH_FRACTION = 0.72f
    const val POKEMON_TITLE_WIDTH_FRACTION = 0.80f

    /**
     * Trainer / Supporter / Item / Stadium.
     * The category word ("AIUTO") sits at the top and the title is lower than a Pokémon HP row,
     * so the title band is taller and nearly full width (there is no HP box).
     * Rules text is the lower-middle band. The set and collector are a thin bottom-left corner,
     * under the rules box rather than in the creature-card bottom strip.
     */
    const val TRAINER_TITLE_HEIGHT_FRACTION = 0.32f
    const val TRAINER_TITLE_WIDTH_FRACTION = 0.92f
    const val TRAINER_RULES_TOP_FRACTION = 0.46f
    const val TRAINER_RULES_BOTTOM_FRACTION = 0.82f
    const val TRAINER_BOTTOM_HEIGHT_FRACTION = 0.18f
    const val TRAINER_BOTTOM_WIDTH_FRACTION = 0.56f

    /**
     * Top strip for the card title, left side only.
     * Pokémon ~22% tall (title row). MTG ~14% (name bar — avoid rules text).
     * The right of the bar is the mana cost or the HP value, so it is left out.
     */
    fun topNameStrip(cardCrop: android.graphics.Bitmap, mtg: Boolean = false): android.graphics.Bitmap {
        val fraction = if (mtg) 0.14f else 0.22f
        val widthFraction = if (mtg) MTG_TITLE_WIDTH_FRACTION else POKEMON_TITLE_WIDTH_FRACTION
        val stripH = (cardCrop.height * fraction).toInt().coerceAtLeast(1).coerceAtMost(cardCrop.height)
        val stripW = (cardCrop.width * widthFraction).toInt().coerceIn(1, cardCrop.width)
        return android.graphics.Bitmap.createBitmap(cardCrop, 0, 0, stripW, stripH)
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

    /** Title band for Trainer / Supporter / Item / Stadium: category word plus the name. */
    fun trainerTitleStrip(cardCrop: android.graphics.Bitmap): android.graphics.Bitmap {
        val stripH = (cardCrop.height * TRAINER_TITLE_HEIGHT_FRACTION)
            .toInt()
            .coerceIn(1, cardCrop.height)
        val stripW = (cardCrop.width * TRAINER_TITLE_WIDTH_FRACTION)
            .toInt()
            .coerceIn(1, cardCrop.width)
        return android.graphics.Bitmap.createBitmap(cardCrop, 0, 0, stripW, stripH)
    }

    /** Rules text under the art, above the collector line. */
    fun trainerRulesStrip(cardCrop: android.graphics.Bitmap): android.graphics.Bitmap {
        val top = (cardCrop.height * TRAINER_RULES_TOP_FRACTION)
            .toInt()
            .coerceIn(0, cardCrop.height - 1)
        val bottom = (cardCrop.height * TRAINER_RULES_BOTTOM_FRACTION)
            .toInt()
            .coerceIn(top + 1, cardCrop.height)
        return android.graphics.Bitmap.createBitmap(cardCrop, 0, top, cardCrop.width, bottom - top)
    }

    /** Bottom-left set + collector on a trainer card. Thinner than the creature bottom strip. */
    fun trainerBottomLeftStrip(cardCrop: android.graphics.Bitmap): android.graphics.Bitmap {
        val stripH = (cardCrop.height * TRAINER_BOTTOM_HEIGHT_FRACTION)
            .toInt()
            .coerceIn(1, cardCrop.height)
        val y = (cardCrop.height - stripH).coerceAtLeast(0)
        val w = (cardCrop.width * TRAINER_BOTTOM_WIDTH_FRACTION)
            .toInt()
            .coerceIn(1, cardCrop.width)
        return android.graphics.Bitmap.createBitmap(cardCrop, 0, y, w, stripH)
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

    /**
     * Zona del codice stampato (sinistra, alto, destra, basso in frazioni della carta) per i giochi
     * con il catalogo CardTrader. Misurata sulle immagini CardTrader:
     * One Piece `OP12-023 UC` in basso a destra (~98%), Dragon Ball `FB02-091` a destra (~90%),
     * Yu-Gi-Oh! `YGLD-ENG01` sotto il disegno a destra (~73%), Union Arena `UE20BT/TSK-1-090` in basso a sinistra (~97%).
     */
    fun ctCodeRegion(game: com.scantrader.app.data.model.Game): FloatArray? = when (game) {
        com.scantrader.app.data.model.Game.ONE_PIECE -> floatArrayOf(0.50f, 0.89f, 1f, 1f)
        com.scantrader.app.data.model.Game.DRAGON_BALL -> floatArrayOf(0.55f, 0.83f, 1f, 1f)
        com.scantrader.app.data.model.Game.YUGIOH -> floatArrayOf(0.40f, 0.68f, 1f, 0.81f)
        com.scantrader.app.data.model.Game.UNION_ARENA -> floatArrayOf(0f, 0.89f, 0.55f, 1f)
        else -> null
    }

    /**
     * Angolo del codice ritagliato dalla carta a PIENA risoluzione: questi codici sono alti ~1,5% della carta
     * e sulla carta ridotta a 480 px (le altre fasce) restano 5-6 pixel, illeggibili.
     */
    fun ctCodeStrip(cardCrop: android.graphics.Bitmap, game: com.scantrader.app.data.model.Game): android.graphics.Bitmap? {
        val r = ctCodeRegion(game) ?: return null
        val x = (cardCrop.width * r[0]).toInt().coerceIn(0, cardCrop.width - 1)
        val y = (cardCrop.height * r[1]).toInt().coerceIn(0, cardCrop.height - 1)
        val w = ((cardCrop.width * r[2]).toInt() - x).coerceIn(1, cardCrop.width - x)
        val h = ((cardCrop.height * r[3]).toInt() - y).coerceIn(1, cardCrop.height - y)
        return android.graphics.Bitmap.createBitmap(cardCrop, x, y, w, h)
    }
}
