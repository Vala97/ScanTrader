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
 *
 * The center control used to follow [processing] on every auto frame, so it
 * looked like it was toggling enabled and disabled. While auto-scan is on,
 * that control is only a steady waiting ring and does not read processing.
 */
object ManualShutter {
    const val CONTENT_DESCRIPTION = "Scatto manuale"
    const val WAITING_DESCRIPTION = "In attesa del riconoscimento"
    const val MAIN_DIAMETER_DP = 76
    const val MANUAL_DIAMETER_DP = 48

    fun showWaitingRing(autoScanOn: Boolean): Boolean = autoScanOn

    fun showManualButton(autoScanOn: Boolean): Boolean = autoScanOn

    /** The center disk is the shutter only when auto-scan is off. */
    fun centerClickable(autoScanOn: Boolean): Boolean = !autoScanOn

    /**
     * Per-frame analysis must not change the auto-scan controls.
     * The manual shutter (auto-scan off) still dims for the one capture in flight.
     */
    fun centerDependsOnProcessing(autoScanOn: Boolean): Boolean = !autoScanOn

    fun manualButtonDependsOnProcessing(): Boolean = false

    fun manualButtonAlpha(): Float = 1f

    /**
     * Auto-scan's per-frame [processing] flag must not swallow the manual button.
     * A second tap is ignored only while that manual shot is already in flight.
     * With auto-scan off, the main shutter still waits out its own capture.
     */
    fun acceptManualTap(autoScanOn: Boolean, processing: Boolean, alreadyCapturing: Boolean): Boolean {
        if (alreadyCapturing) return false
        if (autoScanOn) return true
        return !processing
    }

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
