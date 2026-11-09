package com.gemini.summarizer.presentation.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.database.entity.HistoryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class HistoryUiState(
    val historyList: List<HistoryEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedItem: HistoryEntity? = null
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            database.historyDao().getAllHistoryFlow().collectLatest { list ->
                _uiState.value = _uiState.value.copy(historyList = list)
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        viewModelScope.launch {
            if (query.isBlank()) {
                loadHistory()
            } else {
                database.historyDao().searchHistory(query.trim()).collectLatest { results ->
                    _uiState.value = _uiState.value.copy(historyList = results)
                }
            }
        }
    }

    fun selectHistoryItem(item: HistoryEntity?) {
        _uiState.value = _uiState.value.copy(selectedItem = item)
    }

    fun deleteHistoryItem(item: HistoryEntity) {
        viewModelScope.launch {
            database.historyDao().deleteHistory(item)
            if (_uiState.value.selectedItem?.id == item.id) {
                _uiState.value = _uiState.value.copy(selectedItem = null)
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            database.historyDao().clearAllHistory()
            _uiState.value = _uiState.value.copy(selectedItem = null)
        }
    }
}

