package com.gemini.summarizer.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "gemini_summarizer_settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        val KEY_AUTO_MODEL_PRIORITY = booleanPreferencesKey("auto_model_priority")
        val KEY_DEFAULT_LANGUAGE = stringPreferencesKey("default_language")
        val KEY_DEFAULT_LENGTH = stringPreferencesKey("default_length")
        val KEY_DEFAULT_TYPE = stringPreferencesKey("default_type")
        val KEY_SAVE_HISTORY = booleanPreferencesKey("save_history")
        val KEY_SAVE_ORIGINAL_TEXT = booleanPreferencesKey("save_original_text")
        val KEY_CUSTOM_PROMPT = stringPreferencesKey("custom_prompt")
    }

    val autoModelPriorityFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_AUTO_MODEL_PRIORITY] ?: true
    }

    val defaultLanguageFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_DEFAULT_LANGUAGE] ?: "Auto"
    }

    val defaultLengthFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_DEFAULT_LENGTH] ?: "Medium"
    }

    val defaultTypeFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_DEFAULT_TYPE] ?: "General"
    }

    val saveHistoryFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_SAVE_HISTORY] ?: true
    }

    val saveOriginalTextFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_SAVE_ORIGINAL_TEXT] ?: true
    }

    suspend fun setAutoModelPriority(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_MODEL_PRIORITY] = enabled }
    }

    suspend fun setDefaultLanguage(lang: String) {
        context.dataStore.edit { it[KEY_DEFAULT_LANGUAGE] = lang }
    }

    suspend fun setDefaultLength(length: String) {
        context.dataStore.edit { it[KEY_DEFAULT_LENGTH] = length }
    }

    suspend fun setDefaultType(type: String) {
        context.dataStore.edit { it[KEY_DEFAULT_TYPE] = type }
    }

    suspend fun setSaveHistory(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SAVE_HISTORY] = enabled }
    }

    suspend fun setSaveOriginalText(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SAVE_ORIGINAL_TEXT] = enabled }
    }
}
