package com.scantrader.app.scan

import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
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

    /** Lighting / optics prefs survive confirm → back-to-scan (not cleared by resetResults). */
    var torchOn: Boolean = false
    var lightIntensity: Float = 0.75f
    var autoScan: Boolean = false
    var zoomRatio: Float = 1f
    var cameraId: String? = null

    /** Last language/condition chosen on confirm — reused until settings override. */
    var language: CardLanguage? = null
    var condition: CardCondition? = null

    fun resetResults() {
        ocrText = ""
        parsed = null
        candidates = emptyList()
        errorMessage = null
        queryFingerprint = null
    }
}
