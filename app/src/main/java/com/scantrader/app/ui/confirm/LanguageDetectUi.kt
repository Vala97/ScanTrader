package com.scantrader.app.ui.confirm

import com.scantrader.app.data.model.CardLanguage

/** Status of on-confirm card language detection, shown above the card image. */
enum class LanguageDetectStatus {
    Analyzing,
    Recognized,
    NotFound,

    /** Magic: no collector code or printed words, or they disagree. [LanguageDetectUi.language] is the proposal. */
    Unrecognized,
}

/**
 * Result of the confirm-screen language wait. [recognized] false means the
 * card itself did not show its language and [language] is only the proposal
 * (the previous card's language). Magic does not auto-save that.
 */
data class LanguageVerdict(
    val language: CardLanguage,
    val recognized: Boolean,
)

data class LanguageDetectUi(
    val status: LanguageDetectStatus,
    val language: CardLanguage? = null,
) {
    fun label(): String = when (status) {
        LanguageDetectStatus.Analyzing -> "Lingua: in analisi…"
        LanguageDetectStatus.Recognized ->
            "Lingua: riconosciuta · ${language?.label ?: "—"}"
        LanguageDetectStatus.NotFound -> "Lingua: non trovata"
        LanguageDetectStatus.Unrecognized ->
            "Lingua non riconosciuta · scegli tu (proposta: ${language?.label ?: "—"})"
    }

    companion object {
        fun fromVerdict(verdict: LanguageVerdict): LanguageDetectUi =
            if (verdict.recognized) {
                LanguageDetectUi(LanguageDetectStatus.Recognized, verdict.language)
            } else {
                LanguageDetectUi(LanguageDetectStatus.Unrecognized, verdict.language)
            }

        fun fromDetection(detected: CardLanguage?): LanguageDetectUi =
            if (detected != null) {
                LanguageDetectUi(LanguageDetectStatus.Recognized, detected)
            } else {
                LanguageDetectUi(LanguageDetectStatus.NotFound)
            }
    }
}
