package com.scantrader.app.ui.confirm

/**
 * One countdown per match id. A second composition of the same card does not
 * start another timer, so the same id cannot be saved twice from two schedules.
 */
object AutoSaveSchedule {
    /** Detection may run longer than this; the countdown starts anyway. */
    const val LANGUAGE_WAIT_CAP_MS = 2_500L

    data class Claim(val activeKey: String, val start: Boolean)

    /** Delay after confirm/language wait. [waitSeconds] ≤ 0 means auto-save is off. */
    fun postConfirmDelayMs(waitSeconds: Int): Long =
        waitSeconds.coerceAtLeast(0) * 1_000L

    /**
     * The countdown waits until language detection has finished for this card.
     * [waitedMs] past [capMs] starts it so a hung lookup cannot block the save.
     */
    fun readyToCount(
        detectEnabled: Boolean,
        languageReady: Boolean,
        waitedMs: Long,
        capMs: Long = LANGUAGE_WAIT_CAP_MS,
    ): Boolean {
        if (!detectEnabled || languageReady) return true
        return waitedMs >= capMs
    }

    fun claim(activeKey: String?, matchKey: String): Claim {
        if (activeKey == matchKey) return Claim(activeKey, start = false)
        return Claim(matchKey, start = true)
    }
}
