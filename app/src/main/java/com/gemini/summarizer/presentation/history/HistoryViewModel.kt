package com.gemini.summarizer.presentation.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.database.entity.HistoryEntity
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val historyList: List<HistoryEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedItem: HistoryEntity? = null
)

@OptIn(FlowPreview::class)
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val _searchQuery = MutableStateFlow("")

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _searchQuery
                .debounce(250L)
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        database.historyDao().getAllHistoryFlow()
                    } else {
                        val sanitized = query.trim().replace("%", "\\%").replace("_", "\\_")
                        database.historyDao().searchHistory(sanitized)
                    }
                }
                .collectLatest { list ->
                    _uiState.update { it.copy(historyList = list) }
                }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectHistoryItem(item: HistoryEntity?) {
        _uiState.update { it.copy(selectedItem = item) }
    }

    fun deleteHistoryItem(item: HistoryEntity) {
        viewModelScope.launch {
            database.historyDao().deleteHistory(item)
            if (_uiState.value.selectedItem?.id == item.id) {
                _uiState.update { it.copy(selectedItem = null) }
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            database.historyDao().clearAllHistory()
            _uiState.update { it.copy(selectedItem = null) }
        }
    }
}

