package com.scantrader.app.ui.confirm

/**
 * Timed auto-save on the confirm overlay.
 * Any interaction with the card options pauses it for this card.
 * A new confirm session starts uncancelled, so the next scan can auto-save again.
 */
object AutoSaveControl {
    const val CANCELLED_LABEL = "Salvataggio automatico annullato"
    const val MANUAL_SAVE_LABEL = "Salva in collezione"

    enum class Gesture {
        LANGUAGE,
        CONDITION,
        FOIL,
        QUANTITY,
        PRICE,
        CANDIDATE,
        FIELD,
        SCROLL,
        TAP,
    }

    data class Labels(
        val armed: Boolean,
        val countdownVisible: Boolean,
        val statusLabel: String?,
        val saveButtonLabel: String,
    )

    /** True when this gesture should pause an armed auto-save. */
    fun onUserInteraction(offered: Boolean, gesture: Gesture): Boolean = when (gesture) {
        Gesture.LANGUAGE,
        Gesture.CONDITION,
        Gesture.FOIL,
        Gesture.QUANTITY,
        Gesture.PRICE,
        Gesture.CANDIDATE,
        Gesture.FIELD,
        Gesture.SCROLL,
        Gesture.TAP,
        -> offered
    }

    fun present(
        offered: Boolean,
        cancelled: Boolean,
        selectedMatchesGate: Boolean,
        secondsLeft: Int,
        saving: Boolean,
    ): Labels {
        val counting = offered && !cancelled && selectedMatchesGate && secondsLeft > 0
        val paused = offered && cancelled
        val button = when {
            saving -> "Salvataggio…"
            counting -> "Salva tra ${secondsLeft}s…"
            paused -> MANUAL_SAVE_LABEL
            else -> "OK · Salva in collezione"
        }
        return Labels(
            armed = counting,
            countdownVisible = counting,
            statusLabel = if (paused) CANCELLED_LABEL else null,
            saveButtonLabel = button,
        )
    }

    /** A later card starts with auto-save eligible again. */
    fun rearmedCancelled(): Boolean = false
}
