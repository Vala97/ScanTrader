package com.scantrader.app.scan

/**
 * Auto-scan skips the gallery only when the catalog query will run.
 * A held catalog still embeds, otherwise a ready collector+set with an
 * unstable set code drops both paths.
 * CardTrader games keep the crop so the image can choose a printing.
 */
object HeavyVision {
    fun skip(
        autoMode: Boolean,
        idReady: Boolean,
        queryCatalog: Boolean,
        neuralChoosesPrinting: Boolean,
    ): Boolean = autoMode && idReady && queryCatalog && !neuralChoosesPrinting
}
