package com.scantrader.app.scan

/**
 * The title strip is only the name. Copyright, collector number, and set stay
 * on the full-card read. A second, taller title pass must not replace that text.
 */
object FullCardOcr {
    fun text(fullCard: String, titleStrip: String): String {
        // [titleStrip] is extra help for the name. It is never the card body.
        if (titleStrip.isEmpty() && fullCard.isEmpty()) return ""
        return fullCard
    }
}
