package com.scantrader.app.scan

import android.util.Log
import com.scantrader.app.BuildConfig

/**
 * Logcat tag for Android Studio: filter by `ScanTrader`. The only place that calls
 * `android.util.Log` (`ScanDebugSourceGuardTest` checks it).
 *
 * Debug builds log everything. Release keeps the `decision` line, warnings and
 * errors; the detailed `d`/`shot` lines come back with «Log diagnostici» in Settings.
 * Never log tokens, personal prices or collection contents.
 */
object ScanDebug {
    const val TAG = "ScanTrader"

    enum class Level { DEBUG, DECISION, WARN, ERROR }

    /** Mirrors the «Log diagnostici» setting (`diagnostic_logs`, off by default). */
    @Volatile
    var diagnosticLogs: Boolean = false

    /** Detailed lines need a debug build or the switch; the rest is always written. */
    fun shouldLog(level: Level, debugBuild: Boolean, diagnosticLogs: Boolean): Boolean =
        when (level) {
            Level.DEBUG -> debugBuild || diagnosticLogs
            Level.DECISION, Level.WARN, Level.ERROR -> true
        }

    private fun enabled(level: Level) = shouldLog(level, BuildConfig.DEBUG, diagnosticLogs)

    fun d(message: String) {
        if (enabled(Level.DEBUG)) Log.d(TAG, message)
    }

    /** One line per identified scan, kept in release: read name, pick, set, number, reason, autosave. */
    fun decision(message: String) {
        if (enabled(Level.DECISION)) Log.i(TAG, message)
    }

    fun w(message: String, t: Throwable? = null) {
        if (!enabled(Level.WARN)) return
        if (t != null) Log.w(TAG, message, t) else Log.w(TAG, message)
    }

    fun e(message: String, t: Throwable? = null) {
        if (!enabled(Level.ERROR)) return
        if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
    }

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
