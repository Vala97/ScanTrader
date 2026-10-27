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
import com.scantrader.app.ui.collection.CollectionBrowseState
import com.scantrader.app.ui.collection.decodeCollectionBrowse
import com.scantrader.app.ui.collection.encodeCollectionBrowse
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
            autoSaveConfidencePercent = prefs[AUTO_SAVE_CONFIDENCE]
                ?.takeIf { it in AUTO_SAVE_CONFIDENCE_RANGE }
                ?: AUTO_SAVE_CONFIDENCE_DEFAULT,
            showDebugInfo = prefs[SHOW_DEBUG_INFO] ?: false,
            neuralPromptShown = prefs[NEURAL_PROMPT_SHOWN] ?: false,
            onboardingCompleted = prefs[ONBOARDING_COMPLETED] ?: false,
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

    suspend fun setAutoSaveConfidencePercent(percent: Int) {
        val safe = percent.takeIf { it in AUTO_SAVE_CONFIDENCE_RANGE } ?: return
        context.dataStore.edit { it[AUTO_SAVE_CONFIDENCE] = safe }
    }

    suspend fun setShowDebugInfo(enabled: Boolean) {
        context.dataStore.edit { it[SHOW_DEBUG_INFO] = enabled }
    }

    suspend fun setNeuralPromptShown(shown: Boolean) {
        context.dataStore.edit { it[NEURAL_PROMPT_SHOWN] = shown }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[ONBOARDING_COMPLETED] = completed }
    }

    /** Introduction finished or skipped. Also retires the old pack dialog. */
    suspend fun completeOnboarding() {
        context.dataStore.edit { prefs ->
            prefs[ONBOARDING_COMPLETED] = true
            prefs[NEURAL_PROMPT_SHOWN] = true
        }
    }

    suspend fun currentDefaults(): ScanDefaults = defaults.first()

    suspend fun collectionBrowse(collectionId: Long): CollectionBrowseState {
        val raw = context.dataStore.data.first()[browseKey(collectionId)]
        return decodeCollectionBrowse(raw)
    }

    suspend fun setCollectionBrowse(collectionId: Long, state: CollectionBrowseState) {
        val key = browseKey(collectionId)
        context.dataStore.edit { prefs ->
            if (!state.active) prefs.remove(key) else prefs[key] = encodeCollectionBrowse(state)
        }
    }

    private fun browseKey(collectionId: Long) = stringPreferencesKey("collection_browse_$collectionId")

    private companion object {
        val LANGUAGE = stringPreferencesKey("default_language")
        val CONDITION = stringPreferencesKey("default_condition")
        val CAMERA_ID = stringPreferencesKey("preferred_camera_id")
        val LENS_KEY = stringPreferencesKey("preferred_lens")
        val ZOOM_RATIO = floatPreferencesKey("scan_zoom_ratio")
        val AUTO_SAVE_WAIT_SECONDS = intPreferencesKey("auto_save_wait_seconds")
        val DETECT_LANGUAGE = booleanPreferencesKey("detect_card_language")
        val AUTO_SAVE_CONFIDENCE = intPreferencesKey("auto_save_confidence_percent")
        val SHOW_DEBUG_INFO = booleanPreferencesKey("show_debug_info")
        val NEURAL_PROMPT_SHOWN = booleanPreferencesKey("neural_prompt_shown")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }
}

/** Allowed auto-save wait values (0 = disabled). */
val AUTO_SAVE_WAIT_OPTIONS = listOf(0, 2, 3, 5, 8, 10)

/** Inclusive percent range for the autosave confidence slider. */
val AUTO_SAVE_CONFIDENCE_RANGE = 50..99

/**
 * Current strict bar in [com.scantrader.app.vision.MatchFusion.AUTO_CONFIDENCE_MIN],
 * as a whole percent. 0.74 is exactly 74.
 */
const val AUTO_SAVE_CONFIDENCE_DEFAULT = 74

data class ScanDefaults(
    val language: CardLanguage,
    val condition: CardCondition,
    val cameraId: String? = null,
    val lensKey: String? = null,
    val zoomRatio: Float = 1f,
    val autoSaveWaitSeconds: Int = 0,
    /** When true, confirm preselects the language read on the card. */
    val detectCardLanguage: Boolean = true,
    /** Autosave only when match confidence is at least this percent. */
    val autoSaveConfidencePercent: Int = AUTO_SAVE_CONFIDENCE_DEFAULT,
    /** Technical confirm copy, such as the «Conferma a mano» banner. */
    val showDebugInfo: Boolean = false,
    /** The first-launch pack dialog was answered. Skips the introduction. */
    val neuralPromptShown: Boolean = false,
    /** The introduction was finished, skipped, or replaced an answered pack prompt. */
    val onboardingCompleted: Boolean = false,
)
