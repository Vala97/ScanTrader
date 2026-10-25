package com.scantrader.app.scan

import com.scantrader.app.data.model.ParsedCardText

/**
 * Autoscan may save a card id again only after a different card was actually
 * in frame: the scene moved, or the parsed name changed, since the overlay closed.
 * The same card left under the guide does not create a second copy.
 */
class AutosaveDuplicateGuard {
    private var blockedKey: String? = null
    private var blockedName: String? = null
    private var newCard: Boolean = true

    val blockedCardKey: String? get() = blockedKey

    /** True while the card we just closed is still the one in frame. */
    fun holdingSameCard(): Boolean = blockedKey != null && !newCard

    fun clear() {
        blockedKey = null
        blockedName = null
        newCard = true
    }

    fun onOverlayClosed(cardKey: String?, name: String?) {
        if (cardKey.isNullOrBlank()) return
        blockedKey = cardKey
        blockedName = name
        newCard = false
    }

    fun noteSceneChanged() {
        if (blockedKey != null) newCard = true
    }

    fun observe(parsed: ParsedCardText) {
        if (blockedKey == null || newCard) return
        if (FrameNameGate.disagrees(parsed.nameGuess, blockedName)) newCard = true
    }

    /** Same id is allowed once a new physical card has been seen since the overlay closed. */
    fun allowAutoSave(cardKey: String): Boolean {
        if (blockedKey == null || cardKey != blockedKey) return true
        return newCard
    }
}
