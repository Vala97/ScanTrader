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
}
