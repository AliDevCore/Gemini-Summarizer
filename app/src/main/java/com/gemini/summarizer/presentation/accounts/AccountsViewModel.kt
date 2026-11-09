package com.gemini.summarizer.presentation.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.database.entity.AccountEntity
import com.gemini.summarizer.core.database.entity.ApiKeyEntity
import com.gemini.summarizer.core.security.SecurityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
                        val raw = securityManager.getApiKey(k.keyIdentifier) ?: "Missing"
                        displayList.add(
                            ApiKeyDisplayItem(
                                id = k.id,
                                accountId = account.id,
                                label = account.name,
                                maskedKey = SecurityManager.maskApiKey(raw),
                                isActive = k.isActive,
                                isInvalid = k.isInvalid,
                                statusMessage = k.statusMessage
                            )
                        )
                    }
                }
                _uiState.value = _uiState.value.copy(keysList = displayList)
            }
        }
    }

    fun openAddKeyDialog() {
        _uiState.value = _uiState.value.copy(showAddKeyDialog = true, errorMessage = null)
    }

    fun dismissAddKeyDialog() {
        _uiState.value = _uiState.value.copy(showAddKeyDialog = false)
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

            _uiState.value = _uiState.value.copy(showAddKeyDialog = false)
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
                }
                database.accountDao().deleteAccount(account)
                loadKeys()
            }
        }
    }
}
