package com.scantrader.app.ui.confirm

import com.scantrader.app.data.model.CardLanguage

/** Status of on-confirm card language detection, shown above the card image. */
enum class LanguageDetectStatus {
    Analyzing,
    Recognized,
    NotFound,
}

data class LanguageDetectUi(
    val status: LanguageDetectStatus,
    val language: CardLanguage? = null,
) {
    fun label(): String = when (status) {
        LanguageDetectStatus.Analyzing -> "Lingua: in analisi…"
        LanguageDetectStatus.Recognized ->
            "Lingua: riconosciuta · ${language?.label ?: "—"}"
        LanguageDetectStatus.NotFound -> "Lingua: non trovata"
    }

    companion object {
        fun fromDetection(detected: CardLanguage?): LanguageDetectUi =
            if (detected != null) {
                LanguageDetectUi(LanguageDetectStatus.Recognized, detected)
            } else {
                LanguageDetectUi(LanguageDetectStatus.NotFound)
            }
    }
}
