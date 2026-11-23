package com.gemini.summarizer.presentation.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.database.entity.ModelEntity
import com.gemini.summarizer.core.database.entity.ModelFilter
import com.gemini.summarizer.core.datastore.SettingsDataStore
import com.gemini.summarizer.core.network.GeminiApiClient
import com.gemini.summarizer.core.network.GeminiApiResult
import com.gemini.summarizer.core.security.SecurityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelsUiState(
    val models: List<ModelEntity> = emptyList(),
    val isAutoPriorityEnabled: Boolean = true,
    val isRefreshing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class ModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val settingsDataStore = SettingsDataStore(application)
    private val securityManager = SecurityManager(application)
    private val apiClient = GeminiApiClient()

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    init {
        loadModels()
    }

    private fun loadModels() {
        viewModelScope.launch {
            purgeDeprecatedModels()

            // Ensure verified Cloudflare models exist
            if (database.modelDao().getModelsCount() == 0) {
                database.modelDao().insertModels(AppDatabase.getDefaultModels())
            }

            database.modelDao().getAllModelsFlow().collectLatest { list ->
                _uiState.update { it.copy(models = list) }
            }
        }
    }

    private suspend fun purgeDeprecatedModels() {
        val allModels = database.modelDao().getAllModelsList()
        for (model in allModels) {
            if (!ModelFilter.isAllowedTextModel(model.id, model.displayName)) {
                database.modelDao().deleteModelById(model.id)
            }
        }
        for (model in AppDatabase.getDefaultModels()) {
            database.modelDao().insertModelIfNotExist(model)
        }
    }

    fun toggleAutoPriority(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setAutoModelPriority(enabled)
            _uiState.update { it.copy(isAutoPriorityEnabled = enabled) }
        }
    }

    fun toggleModelEnabled(model: ModelEntity) {
        viewModelScope.launch {
            database.modelDao().setModelEnabled(model.id, !model.isEnabled)
        }
    }

    fun selectAllModels() {
        viewModelScope.launch {
            database.modelDao().enableAllModels()
        }
    }

    fun deselectAllModels() {
        viewModelScope.launch {
            database.modelDao().disableAllModels()
        }
    }

    fun moveModelPriority(model: ModelEntity, moveUp: Boolean) {
        viewModelScope.launch {
            val list = database.modelDao().getAllModelsList().toMutableList()
            val index = list.indexOfFirst { it.id == model.id }
            if (index == -1) return@launch

            val targetIndex = if (moveUp) index - 1 else index + 1
            if (targetIndex in list.indices) {
                val item = list.removeAt(index)
                list.add(targetIndex, item)
                list.forEachIndexed { i, m ->
                    database.modelDao().setModelPriority(m.id, i + 1)
                }
            }
        }
    }

    fun refreshModelsFromApi() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isRefreshing = true,
                statusMessage = "Verifying connection to Cloudflare Workers AI...",
                errorMessage = null
            )

            val keys = database.apiKeyDao().getAllValidKeys()
            if (keys.isEmpty()) {
                purgeDeprecatedModels()
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    statusMessage = "Cloudflare models ready. Add an API Key and Account ID in Accounts to test live connection.",
                    errorMessage = null
                )
                return@launch
            }

            val keyEntity = keys.first()
            val rawKey = securityManager.getApiKey(keyEntity.keyIdentifier)
            val accountId = keyEntity.projectId

            if (rawKey.isNullOrBlank() || accountId.isBlank()) {
                purgeDeprecatedModels()
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    statusMessage = "4 verified Cloudflare models ready.",
                    errorMessage = "Active key is missing Token or Account ID."
                )
                return@launch
            }

            when (val testRes = apiClient.testConnection(apiToken = rawKey, accountId = accountId)) {
                is GeminiApiResult.Success -> {
                    purgeDeprecatedModels()
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        statusMessage = "Connection verified! Cloudflare Workers AI is connected with 4 active models.",
                        errorMessage = null
                    )
                }
                is GeminiApiResult.InvalidKey -> {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        statusMessage = null,
                        errorMessage = "Cloudflare API Token is unauthorized (401/403)."
                    )
                }
                is GeminiApiResult.ModelUnavailable -> {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        statusMessage = null,
                        errorMessage = "Cloudflare Account ID ($accountId) not found (404)."
                    )
                }
                is GeminiApiResult.RateLimited -> {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        statusMessage = null,
                        errorMessage = "Cloudflare rate limit reached (429)."
                    )
                }
                is GeminiApiResult.TransientError -> {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        statusMessage = null,
                        errorMessage = "Network error: ${testRes.message}"
                    )
                }
                is GeminiApiResult.RequestError -> {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        statusMessage = null,
                        errorMessage = testRes.message
                    )
                }
            }
        }
    }
}
