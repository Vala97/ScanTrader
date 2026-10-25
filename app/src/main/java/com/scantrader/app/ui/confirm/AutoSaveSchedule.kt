package com.scantrader.app.ui.confirm

/**
 * One countdown per match id. A second composition of the same card does not
 * start another timer, so the same id cannot be saved twice from two schedules.
 */
object AutoSaveSchedule {
    data class Claim(val activeKey: String, val start: Boolean)

    fun claim(activeKey: String?, matchKey: String): Claim {
        if (activeKey == matchKey) return Claim(activeKey, start = false)
        return Claim(matchKey, start = true)
    }
}
