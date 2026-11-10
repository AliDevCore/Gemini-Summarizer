package com.gemini.summarizer.presentation.accounts

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.database.entity.AccountEntity
import com.gemini.summarizer.core.database.entity.ApiKeyEntity
import com.gemini.summarizer.core.network.GeminiApiClient
import com.gemini.summarizer.core.network.GeminiApiResult
import com.gemini.summarizer.core.security.SecurityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ApiKeyDisplayItem(
    val id: Long,
    val accountId: Long,
    val label: String,
    val maskedKey: String,
    val isActive: Boolean,
    val isInvalid: Boolean,
    val statusMessage: String
)

data class AccountsUiState(
    val keysList: List<ApiKeyDisplayItem> = emptyList(),
    val isLoading: Boolean = false,
    val showAddKeyDialog: Boolean = false,
    val keyPendingDelete: ApiKeyDisplayItem? = null,
    val errorMessage: String? = null
)

class AccountsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val securityManager = SecurityManager(application)
    private val apiClient = GeminiApiClient()

    private val _uiState = MutableStateFlow(AccountsUiState())
    val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

    init {
        // Persistent combined observer flow for all accounts and api_keys
        viewModelScope.launch {
            database.accountDao().getAllAccountsFlow()
                .combine(database.apiKeyDao().getAllApiKeysFlow()) { accounts, keys ->
                    val accountMap = accounts.associateBy { it.id }
                    keys.map { k ->
                        val account = accountMap[k.accountId]
                        val label = account?.name ?: "Key #${k.id}"
                        val raw = securityManager.getApiKey(k.keyIdentifier)
                        val masked = if (raw != null) SecurityManager.maskApiKey(raw) else "Missing in Storage"
                        ApiKeyDisplayItem(
                            id = k.id,
                            accountId = k.accountId,
                            label = label,
                            maskedKey = masked,
                            isActive = k.isActive,
                            isInvalid = k.isInvalid,
                            statusMessage = k.statusMessage
                        )
                    }
                }
                .collectLatest { displayList ->
                    _uiState.update { it.copy(keysList = displayList) }
                }
        }
    }

    fun openAddKeyDialog() {
        _uiState.update { it.copy(showAddKeyDialog = true, errorMessage = null) }
    }

    fun dismissAddKeyDialog() {
        _uiState.update { it.copy(showAddKeyDialog = false, errorMessage = null) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun addDirectApiKey(rawKey: String, customLabel: String?, customProjectId: String? = null) {
        val cleanKey = rawKey.trim()
        if (cleanKey.isBlank()) {
            _uiState.update { it.copy(errorMessage = "API key cannot be empty.") }
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }

                // Check for duplicate API key
                val allAccounts = database.accountDao().getAllAccountsList()
                for (acc in allAccounts) {
                    val existingKeys = database.apiKeyDao().getKeysForAccount(acc.id)
                    for (k in existingKeys) {
                        val stored = securityManager.getApiKey(k.keyIdentifier)
                        if (stored != null && stored.trim() == cleanKey) {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    errorMessage = "This API key has already been added as '${acc.name}'."
                                )
                            }
                            return@launch
                        }
                    }
                }

                val keyNumber = _uiState.value.keysList.size + 1
                val label = if (customLabel.isNullOrBlank()) "Key #$keyNumber" else customLabel.trim()

                // 1. Verify and store the key in encrypted storage first
                val keyIdentifier = UUID.randomUUID().toString()
                val stored = securityManager.storeApiKey(keyIdentifier, cleanKey)
                if (!stored) {
                    throw IllegalStateException("Failed to commit key to secure encrypted storage.")
                }

                // 2. Create account slot
                val accountId = database.accountDao().insertAccount(
                    AccountEntity(
                        name = label,
                        priority = keyNumber,
                        isEnabled = true
                    )
                )

                // 3. Create ApiKeyEntity associated with the account
                val assignedProjectId = if (!customProjectId.isNullOrBlank()) customProjectId.trim() else "project-$accountId"
                database.apiKeyDao().insertApiKey(
                    ApiKeyEntity(
                        accountId = accountId,
                        keyIdentifier = keyIdentifier,
                        projectId = assignedProjectId,
                        isActive = true,
                        statusMessage = "Ready"
                    )
                )

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        showAddKeyDialog = false,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                Log.e("AccountsViewModel", "Error saving API key", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Error saving API key: ${e.message ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    fun testApiKey(item: ApiKeyDisplayItem) {
        viewModelScope.launch {
            val key = database.apiKeyDao().getKeysForAccount(item.accountId).find { it.id == item.id } ?: return@launch
            val raw = securityManager.getApiKey(key.keyIdentifier)
            if (raw.isNullOrBlank()) {
                database.apiKeyDao().setKeyStatus(key.id, isInvalid = true, "Key missing in secure storage")
                return@launch
            }

            database.apiKeyDao().setKeyStatus(key.id, isInvalid = false, "Testing connection...")

            when (val res = apiClient.listModels(raw)) {
                is GeminiApiResult.Success -> {
                    database.apiKeyDao().setKeyStatus(key.id, isInvalid = false, "Verified (${res.data.size} models)")
                }
                is GeminiApiResult.InvalidKey -> {
                    database.apiKeyDao().setKeyStatus(key.id, isInvalid = true, "Invalid API key")
                }
                is GeminiApiResult.RateLimited -> {
                    database.apiKeyDao().setKeyStatus(key.id, isInvalid = false, "Rate limited (429)")
                }
                else -> {
                    database.apiKeyDao().setKeyStatus(key.id, isInvalid = false, "Connection checked")
                }
            }
        }
    }

    fun toggleKeyActive(item: ApiKeyDisplayItem) {
        viewModelScope.launch {
            val key = database.apiKeyDao().getKeysForAccount(item.accountId).find { it.id == item.id }
            if (key != null) {
                database.apiKeyDao().updateApiKey(key.copy(isActive = !key.isActive))
            }
        }
    }

    fun requestDeleteKey(item: ApiKeyDisplayItem) {
        _uiState.update { it.copy(keyPendingDelete = item) }
    }

    fun cancelDeleteKey() {
        _uiState.update { it.copy(keyPendingDelete = null) }
    }

    fun confirmDeleteKey() {
        val item = _uiState.value.keyPendingDelete ?: return
        _uiState.update { it.copy(keyPendingDelete = null) }

        viewModelScope.launch {
            val account = database.accountDao().getAccountById(item.accountId)
            if (account != null) {
                val keys = database.apiKeyDao().getKeysForAccount(account.id)
                for (k in keys) {
                    securityManager.deleteApiKey(k.keyIdentifier)
                    database.apiKeyDao().deleteApiKey(k)
                }
                database.accountDao().deleteAccount(account)
            }
        }
    }

    fun deleteKey(item: ApiKeyDisplayItem) {
        requestDeleteKey(item)
    }
}
