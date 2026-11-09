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

data class AccountWithKeys(
    val account: AccountEntity,
    val keys: List<ApiKeyEntity>
)

data class AccountsUiState(
    val accountsWithKeys: List<AccountWithKeys> = emptyList(),
    val isLoading: Boolean = false,
    val showAddAccountDialog: Boolean = false,
    val showAddKeyDialogForAccount: AccountEntity? = null,
    val errorMessage: String? = null
)

class AccountsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val securityManager = SecurityManager(application)

    private val _uiState = MutableStateFlow(AccountsUiState())
    val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

    init {
        loadAccounts()
    }

    private fun loadAccounts() {
        viewModelScope.launch {
            database.accountDao().getAllAccountsFlow().collectLatest { accounts ->
                val list = mutableListOf<AccountWithKeys>()
                for (acc in accounts) {
                    val keys = database.apiKeyDao().getKeysForAccount(acc.id)
                    list.add(AccountWithKeys(acc, keys))
                }
                _uiState.value = _uiState.value.copy(accountsWithKeys = list)
            }
        }
    }

    fun openAddAccountDialog() {
        _uiState.value = _uiState.value.copy(showAddAccountDialog = true, errorMessage = null)
    }

    fun dismissAddAccountDialog() {
        _uiState.value = _uiState.value.copy(showAddAccountDialog = false)
    }

    fun createAccount(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val count = _uiState.value.accountsWithKeys.size
            database.accountDao().insertAccount(
                AccountEntity(name = name.trim(), priority = count + 1)
            )
            _uiState.value = _uiState.value.copy(showAddAccountDialog = false)
            loadAccounts()
        }
    }

    fun toggleAccountEnabled(account: AccountEntity) {
        viewModelScope.launch {
            database.accountDao().updateAccount(account.copy(isEnabled = !account.isEnabled))
            loadAccounts()
        }
    }

    fun deleteAccount(account: AccountEntity) {
        viewModelScope.launch {
            val keys = database.apiKeyDao().getKeysForAccount(account.id)
            for (k in keys) {
                securityManager.deleteApiKey(k.keyIdentifier)
            }
            database.accountDao().deleteAccount(account)
            loadAccounts()
        }
    }

    fun openAddKeyDialog(account: AccountEntity) {
        _uiState.value = _uiState.value.copy(showAddKeyDialogForAccount = account, errorMessage = null)
    }

    fun dismissAddKeyDialog() {
        _uiState.value = _uiState.value.copy(showAddKeyDialogForAccount = null)
    }

    fun addApiKey(rawKey: String, projectId: String) {
        val account = _uiState.value.showAddKeyDialogForAccount ?: return
        val cleanKey = rawKey.trim()
        val cleanProject = if (projectId.isBlank()) "project-${account.id}" else projectId.trim()

        if (cleanKey.isBlank()) return

        viewModelScope.launch {
            val keyIdentifier = UUID.randomUUID().toString()
            securityManager.storeApiKey(keyIdentifier, cleanKey)

            database.apiKeyDao().insertApiKey(
                ApiKeyEntity(
                    accountId = account.id,
                    keyIdentifier = keyIdentifier,
                    projectId = cleanProject,
                    isActive = true,
                    statusMessage = "Ready"
                )
            )
            _uiState.value = _uiState.value.copy(showAddKeyDialogForAccount = null)
            loadAccounts()
        }
    }

    fun toggleKeyActive(key: ApiKeyEntity) {
        viewModelScope.launch {
            database.apiKeyDao().updateApiKey(key.copy(isActive = !key.isActive))
            loadAccounts()
        }
    }

    fun deleteKey(key: ApiKeyEntity) {
        viewModelScope.launch {
            securityManager.deleteApiKey(key.keyIdentifier)
            database.apiKeyDao().deleteApiKey(key)
            loadAccounts()
        }
    }

    fun getMaskedKey(key: ApiKeyEntity): String {
        val raw = securityManager.getApiKey(key.keyIdentifier) ?: return "Missing"
        return SecurityManager.maskApiKey(raw)
    }
}
