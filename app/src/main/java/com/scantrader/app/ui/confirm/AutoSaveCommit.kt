package com.scantrader.app.ui.confirm

import com.scantrader.app.data.model.CardLanguage

/**
 * Once the language wait ends, the card that was scheduled is saved.
 * A timeout still saves, with the best language already on hand.
 * Clearing the candidate list does not drop it. Cancelling, or showing
 * a different card, does.
 */
object AutoSaveCommit {
    fun shouldCommit(
        cancelled: Boolean,
        saving: Boolean,
        targetPresent: Boolean,
        scheduledKey: String,
        selectedKey: String?,
        userPickedOther: Boolean,
    ): Boolean {
        if (cancelled || saving || !targetPresent || scheduledKey.isBlank()) return false
        if (userPickedOther) return false
        if (selectedKey != null && selectedKey != scheduledKey) return false
        return true
    }

    /** Detected, then OCR, then the last choice, then the settings default. */
    fun languageAfterWait(
        detected: CardLanguage?,
        local: CardLanguage?,
        printed: CardLanguage?,
        session: CardLanguage?,
        fallback: CardLanguage,
    ): CardLanguage = detected ?: local ?: printed ?: session ?: fallback

    /**
     * The printed-name lookup did not finish. Keep the last card that was
     * actually saved. Spanish is a common false read of `IT` on the collector
     * line (`ES`), so it wins only with a real clue in the title or rules.
     */
    fun languageOnTimeout(
        lastSaved: CardLanguage?,
        session: CardLanguage?,
        fallback: CardLanguage,
        spanishClue: Boolean,
    ): CardLanguage {
        if (spanishClue) return CardLanguage.SPANISH
        return lastSaved ?: session ?: fallback
    }

    /** ñ, or a Spanish function word. A bare `ES` token is not a clue. */
    fun spanishClue(ocrName: String?, ocrText: String?): Boolean {
        val raw = listOfNotNull(ocrName, ocrText).filter { it.isNotBlank() }.joinToString("\n")
        if (raw.isEmpty()) return false
        if (raw.contains('ñ') || raw.contains('Ñ')) return true
        return SPANISH_CLUE.containsMatchIn(raw)
    }

    private val SPANISH_CLUE = Regex(
        """(?i)\b(?:los|las|evoluciona|debilidad|retirada)\b""",
    )
}
