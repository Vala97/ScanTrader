package com.scantrader.app.scan

import android.util.Log

/** Logcat tag for Android Studio: filter by `ScanTrader`. */
object ScanDebug {
    const val TAG = "ScanTrader"

    fun d(message: String) = Log.d(TAG, message)
    fun w(message: String, t: Throwable? = null) =
        if (t != null) Log.w(TAG, message, t) else Log.w(TAG, message)
    fun e(message: String, t: Throwable? = null) =
        if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)

    /** One line per shot: `shot: embedMs=… rotationSkipped=yes|no rankerSkipped=yes|no totalMs=…`. */
    fun shot(embedMs: Long, rotationSkipped: Boolean, rankerSkipped: Boolean, totalMs: Long) {
        d(
            "shot: embedMs=$embedMs rotationSkipped=${yesNo(rotationSkipped)} " +
                "rankerSkipped=${yesNo(rankerSkipped)} totalMs=$totalMs",
        )
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"
}

/**
 * Per-shot counters. Defaults mean "did not run": no second orientation and no art downloads.
 * The gallery and the ranker overwrite them when they actually work.
 */
class ShotClock {
    var embedMs: Long = 0
    var rotationSkipped: Boolean = true
    var rankerSkipped: Boolean = true
}
