package com.gemini.summarizer.presentation.home

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.database.entity.HistoryEntity
import com.gemini.summarizer.core.datastore.SettingsDataStore
import com.gemini.summarizer.core.export.DocumentExporter
import com.gemini.summarizer.core.network.GeminiFileData
import com.gemini.summarizer.core.security.SecurityManager
import com.gemini.summarizer.domain.engine.AutoRotationEngine
import com.gemini.summarizer.domain.engine.HierarchicalSummarizer
import com.gemini.summarizer.domain.engine.SummarizationFinalResult
import com.gemini.summarizer.domain.model.SummaryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val inputText: String = "",
    val isLoading: Boolean = false,
    val progressMessage: String = "",
    val selectedFileName: String? = null,
    val selectedFileBase64: String? = null,
    val selectedFileMimeType: String? = null,
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
            summaryResult = null,
            errorMessage = null
        )
    }

    fun removeSelectedFile() {
        _uiState.value = _uiState.value.copy(
            selectedFileName = null,
            selectedFileBase64 = null,
            selectedFileMimeType = null,
            errorMessage = null
        )
    }

    fun processFileUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, progressMessage = "Preparing document for Gemini...")
            withContext(Dispatchers.IO) {
                try {
                    val contentResolver = getApplication<Application>().contentResolver
                    var fileName = "Document"
                    val cursor = contentResolver.query(uri, null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index != -1) {
                                fileName = it.getString(index)
                            }
                        }
                    }

                    val ext = fileName.substringAfterLast(".", "").lowercase()
                    val mimeType = when (ext) {
                        "pdf" -> "application/pdf"
                        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        "txt", "csv", "md" -> "text/plain"
                        else -> contentResolver.getType(uri) ?: "application/octet-stream"
                    }

                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.isEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            progressMessage = "",
                            errorMessage = "Selected file is empty."
                        )
                        return@withContext
                    }

                    val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)

                    // Keep inputText completely empty, file will be sent directly to Gemini
                    _uiState.value = _uiState.value.copy(
                        selectedFileName = fileName,
                        selectedFileBase64 = base64Data,
                        selectedFileMimeType = mimeType,
                        isLoading = false,
                        progressMessage = "",
                        errorMessage = null
                    )
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        progressMessage = "",
                        errorMessage = "Failed to load document: ${e.localizedMessage ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    fun startSummarization() {
        val hasFile = _uiState.value.selectedFileBase64 != null
        val hasText = _uiState.value.inputText.isNotBlank()

        if (!hasFile && !hasText) {
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

            val result = if (hasFile) {
                hierarchicalSummarizer.summarizeFile(
                    fileData = GeminiFileData(
                        mimeType = _uiState.value.selectedFileMimeType ?: "application/pdf",
                        base64Data = _uiState.value.selectedFileBase64!!
                    ),
                    fileName = _uiState.value.selectedFileName ?: "Document",
                    userInstructions = _uiState.value.inputText.ifBlank { null },
                    config = config,
                    onProgressUpdate = { progress ->
                        _uiState.value = _uiState.value.copy(progressMessage = progress)
                    }
                )
            } else {
                hierarchicalSummarizer.summarizeText(
                    inputText = _uiState.value.inputText.trim(),
                    config = config,
                    onProgressUpdate = { progress ->
                        _uiState.value = _uiState.value.copy(progressMessage = progress)
                    }
                )
            }

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
                        val originalTextToSave = if (hasFile) {
                            "[Document: ${_uiState.value.selectedFileName}]"
                        } else if (saveOrig) {
                            _uiState.value.inputText
                        } else {
                            "[Original text omitted per user settings]"
                        }

                        database.historyDao().insertHistory(
                            HistoryEntity(
                                title = title,
                                originalText = originalTextToSave,
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
