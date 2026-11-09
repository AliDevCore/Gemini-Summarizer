package com.gemini.summarizer.presentation.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.database.entity.HistoryEntity
import com.gemini.summarizer.core.datastore.SettingsDataStore
import com.gemini.summarizer.core.export.DocumentExporter
import com.gemini.summarizer.core.file.DocumentExtractor
import com.gemini.summarizer.core.security.SecurityManager
import com.gemini.summarizer.domain.engine.AutoRotationEngine
import com.gemini.summarizer.domain.engine.HierarchicalSummarizer
import com.gemini.summarizer.domain.engine.SummarizationFinalResult
import com.gemini.summarizer.domain.model.SummaryConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class HomeUiState(
    val inputText: String = "",
    val isLoading: Boolean = false,
    val progressMessage: String = "",
    val selectedFileName: String? = null,
    val summaryResult: SummarizationFinalResult? = null,
    val targetLanguage: String = "Auto",
    val summaryLength: String = "Medium",
    val summaryType: String = "General",
    val customPrompt: String = "",
    val errorMessage: String? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val securityManager = SecurityManager(application)
    private val settingsDataStore = SettingsDataStore(application)
    private val documentExtractor = DocumentExtractor(application)
    private val documentExporter = DocumentExporter(application)

    private val rotationEngine = AutoRotationEngine(database, securityManager)
    private val hierarchicalSummarizer = HierarchicalSummarizer(rotationEngine)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val defLang = settingsDataStore.defaultLanguageFlow.first()
            val defLength = settingsDataStore.defaultLengthFlow.first()
            val defType = settingsDataStore.defaultTypeFlow.first()
            _uiState.value = _uiState.value.copy(
                targetLanguage = defLang,
                summaryLength = defLength,
                summaryType = defType
            )
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text, errorMessage = null)
    }

    fun onLanguageSelected(lang: String) {
        _uiState.value = _uiState.value.copy(targetLanguage = lang)
    }

    fun onLengthSelected(length: String) {
        _uiState.value = _uiState.value.copy(summaryLength = length)
    }

    fun onTypeSelected(type: String) {
        _uiState.value = _uiState.value.copy(summaryType = type)
    }

    fun onCustomPromptChanged(prompt: String) {
        _uiState.value = _uiState.value.copy(customPrompt = prompt)
    }

    fun onClearInput() {
        _uiState.value = _uiState.value.copy(
            inputText = "",
            selectedFileName = null,
            summaryResult = null,
            errorMessage = null
        )
    }

    fun processFileUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, progressMessage = "Extracting file content...")
            val extractResult = documentExtractor.extractFromUri(uri)
            extractResult.fold(
                onSuccess = { doc ->
                    if (doc.isScannedOrEmpty) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            progressMessage = "",
                            errorMessage = "The selected document appears to be scanned or contains no extractable text."
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            inputText = doc.text,
                            selectedFileName = doc.fileName,
                            isLoading = false,
                            progressMessage = "",
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        progressMessage = "",
                        errorMessage = err.message ?: "Failed to extract text from document."
                    )
                }
            )
        }
    }

    fun startSummarization() {
        val currentText = _uiState.value.inputText.trim()
        if (currentText.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please enter text or select a document.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null,
                progressMessage = "Initializing Gemini auto-rotation..."
            )

            val config = SummaryConfig(
                targetLanguage = _uiState.value.targetLanguage,
                summaryLength = _uiState.value.summaryLength,
                summaryType = _uiState.value.summaryType,
                customPrompt = _uiState.value.customPrompt.ifBlank { null }
            )

            val result = hierarchicalSummarizer.summarize(
                inputText = currentText,
                config = config,
                onProgressUpdate = { progress ->
                    _uiState.value = _uiState.value.copy(progressMessage = progress)
                }
            )

            result.fold(
                onSuccess = { summaryResult ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        progressMessage = "",
                        summaryResult = summaryResult
                    )

                    // Save to history if enabled
                    if (settingsDataStore.saveHistoryFlow.first()) {
                        val saveOrig = settingsDataStore.saveOriginalTextFlow.first()
                        val title = _uiState.value.selectedFileName ?: "Text Summary"
                        database.historyDao().insertHistory(
                            HistoryEntity(
                                title = title,
                                originalText = if (saveOrig) currentText else "[Original text omitted per user settings]",
                                summaryText = summaryResult.summaryText,
                                modelUsed = summaryResult.modelUsed,
                                accountUsed = summaryResult.accountUsed,
                                projectId = summaryResult.projectId,
                                inputWords = summaryResult.inputWords,
                                outputWords = summaryResult.outputWords,
                                stepsCount = summaryResult.stepsCount,
                                targetLanguage = config.targetLanguage,
                                summaryType = config.summaryType
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        progressMessage = "",
                        errorMessage = error.message ?: "Summarization failed."
                    )
                }
            )
        }
    }

    fun exportSummary(uri: Uri, format: String) {
        val summary = _uiState.value.summaryResult ?: return
        val title = _uiState.value.selectedFileName ?: "Gemini Summary"
        viewModelScope.launch {
            documentExporter.exportToUri(
                uri = uri,
                format = format,
                title = title,
                summaryContent = summary.summaryText,
                modelUsed = summary.modelUsed,
                accountUsed = summary.accountUsed
            )
        }
    }
}
