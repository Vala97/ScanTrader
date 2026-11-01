package com.scantrader.app.scan

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** Short beep when auto-scan recognizes a card. Silent and vibrate stay quiet. */
object RecognitionSound {
    fun shouldPlay(enabled: Boolean, ringerMode: Int): Boolean =
        enabled && ringerMode == AudioManager.RINGER_MODE_NORMAL

    fun play(context: Context, enabled: Boolean) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (!shouldPlay(enabled, audio.ringerMode)) return
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 140)
            val looper = Looper.getMainLooper()
            if (looper != null) {
                Handler(looper).postDelayed({ runCatching { tone.release() } }, 400)
            }
        }
    }
}
