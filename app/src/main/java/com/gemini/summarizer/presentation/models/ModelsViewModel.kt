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
import kotlinx.coroutines.flow.first
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
        observeAutoPriority()
    }

    private fun loadModels() {
        viewModelScope.launch {
            // Clean up deprecated or non-text models using centralized ModelFilter
            purgeDeprecatedModels()

            // Ensure default models exist if database was empty
            if (database.modelDao().getModelsCount() == 0) {
                database.modelDao().insertModels(AppDatabase.getDefaultModels())
            }

            // Only auto-sort if user has auto-priority enabled
            val isAutoEnabled = settingsDataStore.autoModelPriorityFlow.first()
            if (isAutoEnabled) {
                applyAutoPrioritySorting()
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
    }

    private fun observeAutoPriority() {
        viewModelScope.launch {
            settingsDataStore.autoModelPriorityFlow.collectLatest { enabled ->
                _uiState.update { it.copy(isAutoPriorityEnabled = enabled) }
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
            val allModels = database.modelDao().getAllModelsList()
            val currentIndex = allModels.indexOfFirst { it.id == model.id }
            if (currentIndex == -1) return@launch
            val targetIndex = if (moveUp) currentIndex - 1 else currentIndex + 1
            if (targetIndex in allModels.indices) {
                val targetModel = allModels[targetIndex]
                val currentP = model.priority
                val targetP = targetModel.priority
                // Swap priorities properly
                database.modelDao().setModelPriority(model.id, targetP)
                database.modelDao().setModelPriority(targetModel.id, currentP)
                settingsDataStore.setAutoModelPriority(false)
            }
        }
    }

    private fun extractVersionScore(id: String): Double {
        if (!id.startsWith("gemini-", ignoreCase = true)) return 0.0
        if (id.contains("latest")) return 2.99
        val match = Regex("""^gemini-(\d+)(?:\.(\d+))?""", RegexOption.IGNORE_CASE).find(id)
        if (match != null) {
            val major = match.groupValues[1].toDoubleOrNull() ?: 0.0
            val minor = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.0
            return major + (minor / 10.0)
        }
        return 0.0
    }

    private fun extractTierScore(id: String): Int {
        return when {
            id.contains("pro") -> 1
            id.contains("flash-lite") || id.contains("lite") -> 3
            id.contains("flash") -> 2
            else -> 4
        }
    }

    private suspend fun applyAutoPrioritySorting() {
        // Sort descending by model version: 3.8 > 3.7 > 3.6 > 3.5 > 3.1 > 3.0
        val allModels = database.modelDao().getAllModelsList()
        val sorted = allModels.sortedWith(
            compareByDescending<ModelEntity> { m -> extractVersionScore(m.id) }
                .thenBy { m -> extractTierScore(m.id) }
                .thenBy { m -> !m.isStable }
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
                            if (!ModelFilter.isAllowedTextModel(discovered.id, discovered.displayName)) continue

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

                        // Always purge any deprecated models and re-apply version descending priority
                        purgeDeprecatedModels()
                        applyAutoPrioritySorting()

                        _uiState.value = _uiState.value.copy(
                            isRefreshing = false,
                            statusMessage = "Successfully updated model inventory ($discoveredCount active models verified).",
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

