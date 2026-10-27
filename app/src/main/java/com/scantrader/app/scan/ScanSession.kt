package com.scantrader.app.scan

import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.model.Game
import com.scantrader.app.data.model.IdentifiedCard
import com.scantrader.app.data.model.ParsedCardText
import com.scantrader.app.vision.ImageFingerprint
import com.scantrader.app.vision.MatchFusion
import kotlinx.coroutines.flow.MutableStateFlow

class ScanSession {
    var game: Game = Game.MTG

    /**
     * True after the user picks Pokémon or Magic on this scan visit.
     * Survives confirm → pop back to the camera. Cleared when the scan
     * screen's ViewModel is destroyed (leaving scan), not by [resetResults].
     */
    var gameChosen: Boolean = false
    var targetCollectionId: Long? = null
    var ocrText: String = ""
    var parsed: ParsedCardText? = null

    /**
     * Live list the confirm screen collects. [resetResults] clears it, so a lookup
     * for the next card cannot keep showing the previous match.
     */
    val candidatesFlow = MutableStateFlow<List<IdentifiedCard>>(emptyList())
    var candidates: List<IdentifiedCard>
        get() = candidatesFlow.value
        set(value) { candidatesFlow.value = value }

    var errorMessage: String? = null
    /** Gate for the top candidate of the last scan. Drives confirm auto-save. */
    var autoMatch: MatchFusion.AutoMatchDecision? = null
    /** [IdentifiedCard.identityKey] of the card [autoMatch] scored. */
    var autoMatchCardKey: String? = null
    /** Fingerprint of last scan crop — used to re-rank manual catalog searches. */
    var queryFingerprint: ImageFingerprint.Result? = null

    /** Lighting / optics prefs survive confirm → back-to-scan (not cleared by resetResults). */
    var torchOn: Boolean = false
    var lightIntensity: Float = 0.75f
    var autoScan: Boolean = false
    /**
     * Last framing: pinch zoom or the lens-picker preset, whichever the user left.
     * Restored after the camera binds again. Not cleared by [resetResults].
     */
    var zoomRatio: Float = 1f
    var cameraId: String? = null
    /** LensCatalog key: auto, cam:id, or zoom:ratio. Same lifetime as [zoomRatio]. */
    var lensKey: String? = null

    /**
     * First measured scanner view, in pixels. The card guide keeps this
     * rectangle across confirm → scan so a new preview aspect cannot move it.
     * Not cleared by [resetResults].
     */
    var guideViewWidth: Float = 0f
    var guideViewHeight: Float = 0f

    /** Last language/condition chosen on confirm — reused until settings override. */
    var language: CardLanguage? = null

    /** Evidence for the last confident [language]. A failed lookup must not replace it. */
    var languageEvidence: String? = null
    var condition: CardCondition? = null

    /**
     * True while a background rules-strip OCR is still filling [ocrText].
     * Confirm waits briefly so Italian keywords can win before language detection.
     */
    @Volatile
    var rulesPending: Boolean = false

    /** Bumped when results reset or a new rules OCR starts, so a stale job cannot append. */
    @Volatile
    var ocrGeneration: Int = 0

    fun resetResults() {
        ocrText = ""
        parsed = null
        candidates = emptyList()
        errorMessage = null
        queryFingerprint = null
        autoMatch = null
        autoMatchCardKey = null
        rulesPending = false
        ocrGeneration++
    }
}
