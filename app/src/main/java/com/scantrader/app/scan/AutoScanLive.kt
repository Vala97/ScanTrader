package com.scantrader.app.scan

import com.scantrader.app.data.model.CardLanguage

/** Last card written into a collection during this scan visit. */
data class LastSavedCard(
    val name: String,
    val setCode: String,
    val collectorNumber: String,
    val languageLabel: String,
    val language: CardLanguage,
    val savedAtMs: Long,
    val identityKey: String,
)

enum class FramedKind { SAVED, NEW, ANALYZING }

/** What the camera is holding, next to the last saved line. */
data class FramedCard(val kind: FramedKind, val name: String?)

object AutoScanLive {
    fun framed(sameAsSaved: Boolean, recognizedName: String?): FramedCard = when {
        sameAsSaved -> FramedCard(FramedKind.SAVED, recognizedName?.trim()?.takeIf { it.isNotEmpty() })
        recognizedName.isNullOrBlank() -> FramedCard(FramedKind.ANALYZING, null)
        else -> FramedCard(FramedKind.NEW, recognizedName.trim())
    }
}
