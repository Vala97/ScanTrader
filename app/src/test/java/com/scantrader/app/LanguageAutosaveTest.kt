package com.scantrader.app

import android.media.AudioManager
import com.scantrader.app.data.catalog.CardLanguageDetector
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import com.scantrader.app.data.prefs.ScanDefaults
import com.scantrader.app.scan.RecognitionSound
import com.scantrader.app.ui.confirm.AutoSaveSchedule
import com.scantrader.app.ui.confirm.LanguageAutosave
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageAutosaveTest {
    @Test
    fun nameEvidenceStartsTheCountdownWithoutTheNetwork() {
        runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = System.currentTimeMillis()
        val result = LanguageAutosave.resolve(
            local = CardLanguageDetector.Detection(CardLanguage.ITALIAN, "name=it"),
            capMs = AutoSaveSchedule.LANGUAGE_WAIT_CAP_MS,
            scope = scope,
            remote = {
                Thread.sleep(5_000)
                CardLanguageDetector.Detection(CardLanguage.FRENCH, "name=fr")
            },
        )
        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 500)
        assertEquals(CardLanguage.ITALIAN, result.language)
        assertEquals("name=it", result.source)
        assertTrue(
            AutoSaveSchedule.readyToCount(
                detectEnabled = true,
                languageReady = true,
                waitedMs = result.waitedMs,
            ),
        )
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun localLanguageDoesNotStartPrintedLookup() {
        runBlocking {
            var calls = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val result = LanguageAutosave.resolve(
                local = CardLanguageDetector.Detection(CardLanguage.ITALIAN, "name=it"),
                scope = scope,
                remote = {
                    calls++
                    CardLanguageDetector.Detection(CardLanguage.FRENCH, "name=fr")
                },
            )
            delay(150)
            assertEquals(0, calls)
            assertEquals(CardLanguage.ITALIAN, result.language)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun slowDetectorHitsTheCapInsteadOfTheNetwork() {
        runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = System.currentTimeMillis()
        val result = LanguageAutosave.resolve(
            local = null,
            capMs = 300,
            scope = scope,
            remote = {
                Thread.sleep(5_000)
                CardLanguageDetector.Detection(CardLanguage.ITALIAN, "name=it")
            },
        )
        val elapsed = System.currentTimeMillis() - started
        assertTrue("waited ${elapsed}ms", elapsed < 1_200)
        assertNull(result.language)
        assertEquals("timeout", result.source)
        assertTrue(
            AutoSaveSchedule.readyToCount(
                detectEnabled = true,
                languageReady = false,
                waitedMs = AutoSaveSchedule.LANGUAGE_WAIT_CAP_MS,
            ),
        )
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun recognitionSoundDefaultsOnAndStaysQuietWhenNotRinging() {
        val defaults = ScanDefaults(CardLanguage.ENGLISH, CardCondition.NEAR_MINT)
        assertTrue(defaults.recognitionSound)
        assertTrue(RecognitionSound.shouldPlay(enabled = true, ringerMode = AudioManager.RINGER_MODE_NORMAL))
        assertTrue(!RecognitionSound.shouldPlay(enabled = true, ringerMode = AudioManager.RINGER_MODE_SILENT))
        assertTrue(!RecognitionSound.shouldPlay(enabled = true, ringerMode = AudioManager.RINGER_MODE_VIBRATE))
        assertTrue(!RecognitionSound.shouldPlay(enabled = false, ringerMode = AudioManager.RINGER_MODE_NORMAL))
    }
}
