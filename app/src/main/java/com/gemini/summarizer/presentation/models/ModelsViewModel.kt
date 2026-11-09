package com.gemini.summarizer.presentation.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.database.entity.ModelEntity
import com.gemini.summarizer.core.datastore.SettingsDataStore
import com.gemini.summarizer.core.network.GeminiApiClient
import com.gemini.summarizer.core.network.GeminiApiResult
import com.gemini.summarizer.core.security.SecurityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
        observeAutoPriority()
    }

    private fun loadModels() {
        viewModelScope.launch {
            // Ensure default models exist if database was empty
            if (database.modelDao().getModelsCount() == 0) {
                database.modelDao().insertModels(AppDatabase.getDefaultModels())
            }

            database.modelDao().getAllModelsFlow().collectLatest { list ->
                _uiState.value = _uiState.value.copy(models = list)
            }
        }
    }

    private fun observeAutoPriority() {
        viewModelScope.launch {
            settingsDataStore.autoModelPriorityFlow.collectLatest { enabled ->
                _uiState.value = _uiState.value.copy(isAutoPriorityEnabled = enabled)
            }
        }
    }

    fun toggleAutoPriority(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setAutoModelPriority(enabled)
            if (enabled) {
                applyAutoPrioritySorting()
            }
        }
    }

    fun toggleModelEnabled(model: ModelEntity) {
        viewModelScope.launch {
            database.modelDao().setModelEnabled(model.id, !model.isEnabled)
        }
    }

    fun moveModelPriority(model: ModelEntity, moveUp: Boolean) {
        viewModelScope.launch {
            val currentPriority = model.priority
            val targetPriority = if (moveUp) (currentPriority - 1).coerceAtLeast(1) else currentPriority + 1
            database.modelDao().setModelPriority(model.id, targetPriority)
            settingsDataStore.setAutoModelPriority(false)
        }
    }

    private suspend fun applyAutoPrioritySorting() {
        // Auto priority rule: Strongest / Newest first (2.5-pro -> 2.0-flash -> 1.5-pro -> 1.5-flash -> flash-lite / 8b)
        val currentModels = database.modelDao().getEnabledTextModels()
        val sorted = currentModels.sortedWith(
            compareBy<ModelEntity> { m ->
                when {
                    m.id.contains("2.5") -> 1
                    m.id.contains("2.0") -> 2
                    m.id.contains("1.5-pro") -> 3
                    m.id.contains("1.5-flash") && !m.id.contains("8b") -> 4
                    m.id.contains("8b") || m.id.contains("lite") -> 5
                    else -> 6
                }
            }.thenBy { m -> !m.isStable }
        )

        for ((index, item) in sorted.withIndex()) {
            database.modelDao().setModelPriority(item.id, index + 1)
        }
    }

    fun refreshModelsFromApi() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isRefreshing = true,
                statusMessage = "Discovering latest models from Gemini Developer API...",
                errorMessage = null
            )

            // Find an active API key
            val keys = database.apiKeyDao().getAllValidKeys()
            if (keys.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    statusMessage = null,
                    errorMessage = "No active API Keys found. Add a valid API key in Accounts to discover models."
                )
                return@launch
            }

            var discoveredCount = 0
            for (keyEntity in keys) {
                val rawKey = securityManager.getApiKey(keyEntity.keyIdentifier) ?: continue
                when (val result = apiClient.listModels(rawKey)) {
                    is GeminiApiResult.Success -> {
                        for (discovered in result.data) {
                            val isPreview = discovered.id.contains("preview") || discovered.id.contains("exp")
                            val modelEntity = ModelEntity(
                                id = discovered.id,
                                displayName = discovered.displayName,
                                description = discovered.description,
                                inputTokenLimit = discovered.inputTokenLimit,
                                outputTokenLimit = discovered.outputTokenLimit,
                                isPreview = isPreview,
                                isStable = !isPreview,
                                supportsTextGeneration = true,
                                priority = 10,
                                isEnabled = true
                            )
                            database.modelDao().insertModelIfNotExist(modelEntity)
                            discoveredCount++
                        }

                        if (_uiState.value.isAutoPriorityEnabled) {
                            applyAutoPrioritySorting()
                        }

                        _uiState.value = _uiState.value.copy(
                            isRefreshing = false,
                            statusMessage = "Successfully updated model inventory ($discoveredCount models verified).",
                            errorMessage = null
                        )
                        return@launch
                    }
                    is GeminiApiResult.InvalidKey -> {
                        database.apiKeyDao().setKeyStatus(keyEntity.id, isInvalid = true, "Key rejected during model listing")
                    }
                    else -> {
                        // Try next key
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                isRefreshing = false,
                statusMessage = null,
                errorMessage = "Could not list models with current API keys. Please verify your keys and network."
            )
        }
    }
}

