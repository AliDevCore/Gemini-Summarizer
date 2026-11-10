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
    val errorMessage: String? = null
)

class AccountsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val securityManager = SecurityManager(application)
    private val apiClient = GeminiApiClient()

    private val _uiState = MutableStateFlow(AccountsUiState())
    val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

    init {
        loadKeys()
    }

    private fun loadKeys() {
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
        _uiState.update { it.copy(showAddKeyDialog = false) }
    }

    fun addDirectApiKey(rawKey: String, customLabel: String?) {
        val cleanKey = rawKey.trim()
        if (cleanKey.isBlank()) return

        viewModelScope.launch {
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

            val keyIdentifier = UUID.randomUUID().toString()
            securityManager.storeApiKey(keyIdentifier, cleanKey)

            database.apiKeyDao().insertApiKey(
                ApiKeyEntity(
                    accountId = accountId,
                    keyIdentifier = keyIdentifier,
                    projectId = "project-$accountId",
                    isActive = true,
                    statusMessage = "Ready"
                )
            )

            _uiState.update { it.copy(showAddKeyDialog = false) }
            loadKeys()
        }
    }

    fun testApiKey(item: ApiKeyDisplayItem) {
        viewModelScope.launch {
            val key = database.apiKeyDao().getKeysForAccount(item.accountId).find { it.id == item.id } ?: return@launch
            val raw = securityManager.getApiKey(key.keyIdentifier)
            if (raw.isNullOrBlank()) {
                database.apiKeyDao().setKeyStatus(key.id, isInvalid = true, "Key missing in secure storage")
                loadKeys()
                return@launch
            }

            database.apiKeyDao().setKeyStatus(key.id, isInvalid = false, "Testing connection...")
            loadKeys()

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
            loadKeys()
        }
    }

    fun toggleKeyActive(item: ApiKeyDisplayItem) {
        viewModelScope.launch {
            val key = database.apiKeyDao().getKeysForAccount(item.accountId).find { it.id == item.id }
            if (key != null) {
                database.apiKeyDao().updateApiKey(key.copy(isActive = !key.isActive))
                loadKeys()
            }
        }
    }

    fun deleteKey(item: ApiKeyDisplayItem) {
        viewModelScope.launch {
            val account = database.accountDao().getAccountById(item.accountId)
            if (account != null) {
                val keys = database.apiKeyDao().getKeysForAccount(account.id)
                for (k in keys) {
                    securityManager.deleteApiKey(k.keyIdentifier)
                    database.apiKeyDao().deleteApiKey(k)
                }
                database.accountDao().deleteAccount(account)
                loadKeys()
            }
        }
    }
}
