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
