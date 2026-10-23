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
}
