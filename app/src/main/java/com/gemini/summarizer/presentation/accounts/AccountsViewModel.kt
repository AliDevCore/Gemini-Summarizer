package com.gemini.summarizer.presentation.accounts

import android.app.Application
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
        // Single persistent observer flow for all accounts and keys
        viewModelScope.launch {
            database.accountDao().getAllAccountsFlow().collectLatest { accounts ->
                val displayList = mutableListOf<ApiKeyDisplayItem>()
                for (account in accounts) {
                    val keys = database.apiKeyDao().getKeysForAccount(account.id)
                    for (k in keys) {
                        val raw = securityManager.getApiKey(k.keyIdentifier)
                        val masked = if (raw != null) SecurityManager.maskApiKey(raw) else "Missing in Storage"
                        displayList.add(
                            ApiKeyDisplayItem(
                                id = k.id,
                                accountId = account.id,
                                label = account.name,
                                maskedKey = masked,
                                isActive = k.isActive,
                                isInvalid = k.isInvalid,
                                statusMessage = k.statusMessage
                            )
                        )
                    }
                }
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

    fun addDirectApiKey(rawKey: String, customLabel: String?, customProjectId: String? = null) {
        val cleanKey = rawKey.trim()
        if (cleanKey.isBlank()) return

        viewModelScope.launch {
            // Check for duplicate API key
            val allAccounts = database.accountDao().getAllAccountsList()
            for (acc in allAccounts) {
                val existingKeys = database.apiKeyDao().getKeysForAccount(acc.id)
                for (k in existingKeys) {
                    val stored = securityManager.getApiKey(k.keyIdentifier)
                    if (stored != null && stored.trim() == cleanKey) {
                        _uiState.update {
                            it.copy(errorMessage = "This API key has already been added as '${acc.name}'.")
                        }
                        return@launch
                    }
                }
            }

            val keyNumber = _uiState.value.keysList.size + 1
            val label = if (customLabel.isNullOrBlank()) "Key #$keyNumber" else customLabel.trim()

            // Create account slot automatically for this key
            val accountId = database.accountDao().insertAccount(
                AccountEntity(
                    name = label,
                    priority = keyNumber,
                    isEnabled = true
                )
            )

            val assignedProjectId = if (!customProjectId.isNullOrBlank()) customProjectId.trim() else "project-$accountId"
            val keyIdentifier = UUID.randomUUID().toString()
            securityManager.storeApiKey(keyIdentifier, cleanKey)

            database.apiKeyDao().insertApiKey(
                ApiKeyEntity(
                    accountId = accountId,
                    keyIdentifier = keyIdentifier,
                    projectId = assignedProjectId,
                    isActive = true,
                    statusMessage = "Ready"
                )
            )

            _uiState.update { it.copy(showAddKeyDialog = false, errorMessage = null) }
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
