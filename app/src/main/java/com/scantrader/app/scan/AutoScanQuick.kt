package com.scantrader.app.scan

/**
 * Copy and timing for the scan-screen auto-scan button.
 * The setting itself lives on [ScanSession] via ScanViewModel.setAutoScan,
 * the same flag as Impostazioni scan.
 */
object AutoScanQuick {
    const val ACTIVE = "Auto-scan attivo"
    const val INACTIVE = "Auto-scan disattivato"

    fun message(enabled: Boolean): String = if (enabled) ACTIVE else INACTIVE

    fun contentDescription(enabled: Boolean): String = message(enabled)

    /**
     * An open confirmation keeps the mode it was opened with.
     * The new setting is used by the next card.
     */
    fun modeForCard(setting: Boolean, openedWith: Boolean, confirmOpen: Boolean): Boolean =
        if (confirmOpen) openedWith else setting
}

/**
 * Manual shutter while auto-scan stays on.
 * The shot always opens confirm and never counts as an auto-scan accept.
 */
object ManualShutter {
    const val CONTENT_DESCRIPTION = "Scatto manuale"

    fun diameterDp(autoScanOn: Boolean): Int = if (autoScanOn) 52 else 76

    /** Secondary, faded control while auto-scan is the primary mode. */
    fun alpha(autoScanOn: Boolean): Float = if (autoScanOn) 0.5f else 1f

    /** No auto frame, and therefore no autosave, while the shutter capture is in flight. */
    fun allowAutoFrame(autoScanOn: Boolean, captureInFlight: Boolean): Boolean =
        autoScanOn && !captureInFlight

    /** Confirm autosave follows an auto-scan hit, not a manual shot, even if the switch stays on. */
    fun confirmFromAutoScan(autoScanOn: Boolean, manualShot: Boolean): Boolean =
        autoScanOn && !manualShot

    /** The same-printing guard is only for the next auto-scan accept. */
    fun blockRepeatPrinting(autoMode: Boolean, sameAsLast: Boolean): Boolean =
        autoMode && sameAsLast
}
