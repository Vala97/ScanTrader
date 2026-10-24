package com.scantrader.app.ui.confirm

/**
 * Timed auto-save on the confirm overlay.
 * Only a real gesture on the card options pauses it.
 * Language detection, price refresh, and image load leave the countdown running.
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

    /** Updates applied by the app, not by a finger on the options. */
    enum class Program {
        DETECTED_LANGUAGE,
        PRICE,
        IMAGE,
    }

    data class Labels(
        /** Eligible to run the countdown. True before the first tick, so the timer can start. */
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

    /** Programmatic updates never cancel. Returns the previous cancelled flag unchanged. */
    fun onProgrammaticUpdate(cancelled: Boolean, update: Program): Boolean = when (update) {
        Program.DETECTED_LANGUAGE,
        Program.PRICE,
        Program.IMAGE,
        -> cancelled
    }

    fun present(
        offered: Boolean,
        cancelled: Boolean,
        selectedMatchesGate: Boolean,
        secondsLeft: Int,
        saving: Boolean,
    ): Labels {
        val eligible = offered && !cancelled && selectedMatchesGate
        val counting = eligible && secondsLeft > 0
        val paused = offered && cancelled
        val button = when {
            saving -> "Salvataggio…"
            counting -> "Salva tra ${secondsLeft}s…"
            paused -> MANUAL_SAVE_LABEL
            else -> "OK · Salva in collezione"
        }
        return Labels(
            armed = eligible,
            countdownVisible = counting,
            statusLabel = if (paused) CANCELLED_LABEL else null,
            saveButtonLabel = button,
        )
    }

    /** A later card starts with auto-save eligible again. */
    fun rearmedCancelled(): Boolean = false
}
