package com.scantrader.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.scantrader.app.data.model.CardCondition
import com.scantrader.app.data.model.CardLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class UserPreferences(private val context: Context) {
    val defaults: Flow<ScanDefaults> = context.dataStore.data.map { prefs ->
        ScanDefaults(
            language = prefs[LANGUAGE]?.let { runCatching { CardLanguage.valueOf(it) }.getOrNull() }
                ?: CardLanguage.ENGLISH,
            condition = prefs[CONDITION]?.let { runCatching { CardCondition.valueOf(it) }.getOrNull() }
                ?: CardCondition.NEAR_MINT,
        )
    }

    suspend fun setLanguage(language: CardLanguage) {
        context.dataStore.edit { it[LANGUAGE] = language.name }
    }

    suspend fun setCondition(condition: CardCondition) {
        context.dataStore.edit { it[CONDITION] = condition.name }
    }

    private companion object {
        val LANGUAGE = stringPreferencesKey("default_language")
        val CONDITION = stringPreferencesKey("default_condition")
    }
}

data class ScanDefaults(
    val language: CardLanguage,
    val condition: CardCondition,
)
