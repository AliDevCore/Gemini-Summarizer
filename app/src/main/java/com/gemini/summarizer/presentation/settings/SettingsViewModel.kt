package com.gemini.summarizer.presentation.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.datastore.SettingsDataStore
import com.gemini.summarizer.core.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val defaultLanguage: String = "Auto",
    val defaultLength: String = "Medium",
    val defaultType: String = "General",
    val saveHistory: Boolean = true,
    val saveOriginalText: Boolean = true,
    val message: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsDataStore = SettingsDataStore(application)
    private val securityManager = SecurityManager(application)
    private val database = AppDatabase.getInstance(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.defaultLanguageFlow.collectLatest {
                _uiState.value = _uiState.value.copy(defaultLanguage = it)
            }
        }
        viewModelScope.launch {
            settingsDataStore.defaultLengthFlow.collectLatest {
                _uiState.value = _uiState.value.copy(defaultLength = it)
            }
        }
        viewModelScope.launch {
            settingsDataStore.defaultTypeFlow.collectLatest {
                _uiState.value = _uiState.value.copy(defaultType = it)
            }
        }
        viewModelScope.launch {
            settingsDataStore.saveHistoryFlow.collectLatest {
                _uiState.value = _uiState.value.copy(saveHistory = it)
            }
        }
        viewModelScope.launch {
            settingsDataStore.saveOriginalTextFlow.collectLatest {
                _uiState.value = _uiState.value.copy(saveOriginalText = it)
            }
        }
    }

    fun setDefaultLanguage(lang: String) {
        viewModelScope.launch { settingsDataStore.setDefaultLanguage(lang) }
    }

    fun setDefaultLength(length: String) {
        viewModelScope.launch { settingsDataStore.setDefaultLength(length) }
    }

    fun setDefaultType(type: String) {
        viewModelScope.launch { settingsDataStore.setDefaultType(type) }
    }

    fun setSaveHistory(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setSaveHistory(enabled) }
    }

    fun setSaveOriginalText(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setSaveOriginalText(enabled) }
    }

    fun clearAllSecureKeys() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                securityManager.clearAllKeys()
                database.apiKeyDao().deleteAllKeys()
                database.quotaStateDao().clearAll()
            }
            _uiState.value = _uiState.value.copy(message = "All API Keys purged from Keystore and database.")
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                securityManager.clearAllKeys()
                database.clearAllTables()
                // Re-seed default models so models table is never left completely empty
                database.modelDao().insertModels(AppDatabase.getDefaultModels())
                // Reset settings to defaults
                settingsDataStore.setDefaultLanguage("Auto")
                settingsDataStore.setDefaultLength("Medium")
                settingsDataStore.setDefaultType("General")
            }
            _uiState.value = _uiState.value.copy(message = "All app databases, settings, and secure keys cleared.")
        }
    }
}

