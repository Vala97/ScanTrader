package com.scantrader.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class UserPreferences(private val context: Context) {
    val defaults: Flow<ScanDefaults> = context.dataStore.data.map { prefs ->
        ScanDefaults(
            language = prefs[LANGUAGE]?.let { runCatching { CardLanguage.valueOf(it) }.getOrNull() }
                ?: CardLanguage.ENGLISH,
            condition = prefs[CONDITION]?.let { runCatching { CardCondition.valueOf(it) }.getOrNull() }
                ?: CardCondition.NEAR_MINT,
            cameraId = prefs[CAMERA_ID]?.takeIf { it.isNotBlank() },
            lensKey = prefs[LENS_KEY]?.takeIf { it.isNotBlank() }
                ?: prefs[CAMERA_ID]?.takeIf { it.isNotBlank() }?.let { "cam:$it" },
            zoomRatio = prefs[ZOOM_RATIO]?.takeIf { it.isFinite() && it > 0f } ?: 1f,
            autoSaveWaitSeconds = prefs[AUTO_SAVE_WAIT_SECONDS]
                ?.takeIf { it in AUTO_SAVE_WAIT_OPTIONS }
                ?: 0,
            detectCardLanguage = prefs[DETECT_LANGUAGE] ?: true,
        )
    }

    suspend fun setLanguage(language: CardLanguage) {
        context.dataStore.edit { it[LANGUAGE] = language.name }
    }

    suspend fun setCondition(condition: CardCondition) {
        context.dataStore.edit { it[CONDITION] = condition.name }
    }

    suspend fun setZoomRatio(ratio: Float) {
        val safe = ratio.takeIf { it.isFinite() && it > 0f } ?: return
        context.dataStore.edit { it[ZOOM_RATIO] = safe }
    }

    /** Persists the lens. A null zoom leaves the last pinch-zoom untouched. */
    suspend fun setLensChoice(lensKey: String, cameraId: String?, zoomRatio: Float?) {
        context.dataStore.edit { prefs ->
            prefs[LENS_KEY] = lensKey
            if (cameraId.isNullOrBlank()) prefs.remove(CAMERA_ID) else prefs[CAMERA_ID] = cameraId
            if (zoomRatio != null && zoomRatio.isFinite() && zoomRatio > 0f) {
                prefs[ZOOM_RATIO] = zoomRatio
            }
        }
    }

    suspend fun setAutoSaveWaitSeconds(seconds: Int) {
        val safe = seconds.takeIf { it in AUTO_SAVE_WAIT_OPTIONS } ?: 0
        context.dataStore.edit { it[AUTO_SAVE_WAIT_SECONDS] = safe }
    }

    suspend fun setDetectCardLanguage(enabled: Boolean) {
        context.dataStore.edit { it[DETECT_LANGUAGE] = enabled }
    }

    suspend fun currentDefaults(): ScanDefaults = defaults.first()

    private companion object {
        val LANGUAGE = stringPreferencesKey("default_language")
        val CONDITION = stringPreferencesKey("default_condition")
        val CAMERA_ID = stringPreferencesKey("preferred_camera_id")
        val LENS_KEY = stringPreferencesKey("preferred_lens")
        val ZOOM_RATIO = floatPreferencesKey("scan_zoom_ratio")
        val AUTO_SAVE_WAIT_SECONDS = intPreferencesKey("auto_save_wait_seconds")
        val DETECT_LANGUAGE = booleanPreferencesKey("detect_card_language")
    }
}

/** Allowed auto-save wait values (0 = disabled). */
val AUTO_SAVE_WAIT_OPTIONS = listOf(0, 2, 3, 5, 8, 10)

data class ScanDefaults(
    val language: CardLanguage,
    val condition: CardCondition,
    val cameraId: String? = null,
    val lensKey: String? = null,
    val zoomRatio: Float = 1f,
    val autoSaveWaitSeconds: Int = 0,
    /** When true, confirm preselects the language read on the card. */
    val detectCardLanguage: Boolean = true,
)
