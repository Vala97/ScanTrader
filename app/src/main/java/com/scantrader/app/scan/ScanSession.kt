package com.scantrader.app.scan

import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.vision.ImageFingerprint

class ScanSession {
    var game: Game = Game.MTG
    var sessionMode: Boolean = false
    var targetCollectionId: Long? = null
    var ocrText: String = ""
    var parsed: ParsedCardText? = null
    var candidates: List<IdentifiedCard> = emptyList()
    var errorMessage: String? = null
    /** Fingerprint of last scan crop — used to re-rank manual catalog searches. */
    var queryFingerprint: ImageFingerprint.Result? = null

    /** Lighting prefs survive confirm → back-to-scan (not cleared by resetResults). */
    var torchOn: Boolean = false
    var lightIntensity: Float = 0.75f
    var autoScan: Boolean = false

    fun resetResults() {
        ocrText = ""
        parsed = null
        candidates = emptyList()
        errorMessage = null
        queryFingerprint = null
    }
}
