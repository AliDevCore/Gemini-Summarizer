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
import com.gemini.summarizer.core.file.DocumentExtractor
import com.gemini.summarizer.domain.model.SummaryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HomeUiState(
    val inputText: String = "",
    val isLoading: Boolean = false,
    val progressMessage: String = "",
    val liveLogs: List<String> = emptyList(),
    val selectedFileName: String? = null,
    val selectedFileBase64: String? = null,
    val selectedFileText: String? = null,
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
    private val documentExtractor = DocumentExtractor(application)

    private val rotationEngine = AutoRotationEngine(database, securityManager)
    private val hierarchicalSummarizer = HierarchicalSummarizer(rotationEngine)

    private var summarizationJob: Job? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private fun formatLog(msg: String): String = "[${timeFormat.format(Date())}] $msg"

    init {
        viewModelScope.launch {
            val defLang = settingsDataStore.defaultLanguageFlow.first()
            val defLength = settingsDataStore.defaultLengthFlow.first()
            val defType = settingsDataStore.defaultTypeFlow.first()
            _uiState.update {
                it.copy(
                    targetLanguage = defLang,
                    summaryLength = defLength,
                    summaryType = defType
                )
            }
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text, errorMessage = null) }
    }

    fun onLanguageSelected(lang: String) {
        _uiState.update { it.copy(targetLanguage = lang) }
    }

    fun onLengthSelected(length: String) {
        _uiState.update { it.copy(summaryLength = length) }
    }

    fun onTypeSelected(type: String) {
        _uiState.update { it.copy(summaryType = type) }
    }

    fun onCustomPromptChanged(prompt: String) {
        _uiState.update { it.copy(customPrompt = prompt) }
    }

    fun onClearInput() {
        _uiState.update {
            it.copy(
                inputText = "",
                summaryResult = null,
                errorMessage = null
            )
        }
    }

    fun removeSelectedFile() {
        _uiState.update {
            it.copy(
                selectedFileName = null,
                selectedFileBase64 = null,
                selectedFileText = null,
                selectedFileMimeType = null,
                errorMessage = null
            )
        }
    }

    fun processFileUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, progressMessage = "Preparing document for Gemini...") }
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

                    when (ext) {
                        "pdf" -> {
                            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            if (bytes == null || bytes.isEmpty()) {
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        progressMessage = "",
                                        errorMessage = "Selected PDF file is empty."
                                    )
                                }
                                return@withContext
                            }

                            if (bytes.size > 20 * 1024 * 1024) {
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        progressMessage = "",
                                        errorMessage = "PDF file exceeds the 20 MB inline limit. Please choose a smaller document."
                                    )
                                }
                                return@withContext
                            }

                            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            _uiState.update {
                                it.copy(
                                    selectedFileName = fileName,
                                    selectedFileBase64 = base64Data,
                                    selectedFileText = null,
                                    selectedFileMimeType = "application/pdf",
                                    isLoading = false,
                                    progressMessage = "",
                                    errorMessage = null
                                )
                            }
                        }
                        "docx" -> {
                            val extractResult = documentExtractor.extractFromUri(uri)
                            extractResult.fold(
                                onSuccess = { doc ->
                                    if (doc.text.isBlank()) {
                                        _uiState.update {
                                            it.copy(
                                                isLoading = false,
                                                progressMessage = "",
                                                errorMessage = "Could not extract readable text from DOCX file."
                                            )
                                        }
                                    } else {
                                        _uiState.update {
                                            it.copy(
                                                selectedFileName = fileName,
                                                selectedFileBase64 = null,
                                                selectedFileText = doc.text,
                                                selectedFileMimeType = "text/plain",
                                                isLoading = false,
                                                progressMessage = "",
                                                errorMessage = null
                                            )
                                        }
                                    }
                                },
                                onFailure = { err ->
                                    _uiState.update {
                                        it.copy(
                                            isLoading = false,
                                            progressMessage = "",
                                            errorMessage = "Failed to parse DOCX: ${err.message}"
                                        )
                                    }
                                }
                            )
                        }
                        else -> {
                            // Plain text formats: txt, md, markdown, csv, json, etc.
                            val text = contentResolver.openInputStream(uri)?.use { stream ->
                                stream.bufferedReader(Charsets.UTF_8).readText()
                            } ?: ""

                            if (text.isBlank()) {
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        progressMessage = "",
                                        errorMessage = "Selected text file is empty."
                                    )
                                }
                            } else {
                                _uiState.update {
                                    it.copy(
                                        selectedFileName = fileName,
                                        selectedFileBase64 = null,
                                        selectedFileText = text,
                                        selectedFileMimeType = "text/plain",
                                        isLoading = false,
                                        progressMessage = "",
                                        errorMessage = null
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            progressMessage = "",
                            errorMessage = "Failed to load document: ${e.localizedMessage ?: "Unknown error"}"
                        )
                    }
                }
            }
        }
    }

    fun cancelSummarization() {
        summarizationJob?.cancel()
        summarizationJob = null
        val cancelLog = formatLog("Process cancelled by user.")
        _uiState.update {
            it.copy(
                isLoading = false,
                progressMessage = "",
                errorMessage = "Summarization cancelled by user.",
                liveLogs = it.liveLogs + cancelLog
            )
        }
    }

    private fun isWebUrl(text: String): Boolean {
        val trimmed = text.trim()
        return (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) &&
                !trimmed.contains(" ") && trimmed.contains(".")
    }

    private fun fetchWebPageContent(url: String): Pair<String, String> {
        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            .timeout(15000)
            .followRedirects(true)
            .get()

        val title = doc.title().ifBlank { url }
        doc.select("script, style, nav, footer, noscript, svg, form, iframe").remove()
        val bodyText = doc.body().text()
        return Pair(title, bodyText)
    }

    fun startSummarization() {
        val hasPdf = _uiState.value.selectedFileBase64 != null
        val hasExtractedDoc = _uiState.value.selectedFileText != null
        val hasText = _uiState.value.inputText.isNotBlank()

        if (!hasPdf && !hasExtractedDoc && !hasText) {
            _uiState.update { it.copy(errorMessage = "Please enter text, a web URL, or select a document.") }
            return
        }

        summarizationJob?.cancel()
        val startLog = formatLog("Starting summarization process...")

        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                progressMessage = "Initializing Gemini engine...",
                liveLogs = listOf(startLog)
            )
        }

        val config = SummaryConfig(
            targetLanguage = _uiState.value.targetLanguage,
            summaryLength = _uiState.value.summaryLength,
            summaryType = _uiState.value.summaryType,
            customPrompt = _uiState.value.customPrompt.ifBlank { null }
        )

        summarizationJob = viewModelScope.launch {
            val result = if (hasPdf) {
                // PDF native inlineData
                hierarchicalSummarizer.summarizeFile(
                    fileData = GeminiFileData(
                        mimeType = "application/pdf",
                        base64Data = _uiState.value.selectedFileBase64!!
                    ),
                    fileName = _uiState.value.selectedFileName ?: "Document",
                    userInstructions = _uiState.value.inputText.ifBlank { null },
                    config = config,
                    onProgressUpdate = { progress ->
                        val logEntry = formatLog(progress)
                        _uiState.update {
                            it.copy(
                                progressMessage = progress,
                                liveLogs = it.liveLogs + logEntry
                            )
                        }
                    }
                )
            } else if (hasExtractedDoc) {
                // DOCX or attached TXT file
                val docText = _uiState.value.selectedFileText!!
                val notes = _uiState.value.inputText.ifBlank { null }
                val contentWithNotes = if (notes != null) "User Instructions: $notes\n\nDocument Content:\n$docText" else docText

                hierarchicalSummarizer.summarizeText(
                    inputText = contentWithNotes,
                    config = config,
                    onProgressUpdate = { progress ->
                        val logEntry = formatLog(progress)
                        _uiState.update {
                            it.copy(
                                progressMessage = progress,
                                liveLogs = it.liveLogs + logEntry
                            )
                        }
                    }
                )
            } else {
                // Direct input text or live Web URL
                val rawInput = _uiState.value.inputText.trim()
                val textToSummarize = if (isWebUrl(rawInput)) {
                    val fetchLog = formatLog("Fetching live webpage: $rawInput...")
                    _uiState.update {
                        it.copy(
                            progressMessage = "Fetching webpage content...",
                            liveLogs = it.liveLogs + fetchLog
                        )
                    }

                    try {
                        withContext(Dispatchers.IO) {
                            val (webTitle, webBody) = fetchWebPageContent(rawInput)
                            if (webBody.isBlank()) {
                                throw IllegalStateException("Webpage returned empty text.")
                            }
                            val successLog = formatLog("Retrieved article '$webTitle' (${webBody.length} characters).")
                            _uiState.update { it.copy(liveLogs = it.liveLogs + successLog) }
                            "Web Page Title: $webTitle\nURL: $rawInput\n\nArticle Content:\n$webBody"
                        }
                    } catch (e: Exception) {
                        val failLog = formatLog("Failed to fetch URL ($rawInput): ${e.localizedMessage}")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                progressMessage = "",
                                errorMessage = "Could not fetch webpage: ${e.localizedMessage ?: "Unknown error"}",
                                liveLogs = it.liveLogs + failLog
                            )
                        }
                        return@launch
                    }
                } else {
                    rawInput
                }

                hierarchicalSummarizer.summarizeText(
                    inputText = textToSummarize,
                    config = config,
                    onProgressUpdate = { progress ->
                        val logEntry = formatLog(progress)
                        _uiState.update {
                            it.copy(
                                progressMessage = progress,
                                liveLogs = it.liveLogs + logEntry
                            )
                        }
                    }
                )
            }

            result.fold(
                onSuccess = { summaryResult ->
                    val successLog = formatLog("Summary generated successfully!")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            progressMessage = "",
                            summaryResult = summaryResult,
                            liveLogs = it.liveLogs + successLog
                        )
                    }

                    // Save to history if enabled
                    if (settingsDataStore.saveHistoryFlow.first()) {
                        val saveOrig = settingsDataStore.saveOriginalTextFlow.first()
                        val title = _uiState.value.selectedFileName ?: if (isWebUrl(_uiState.value.inputText)) "Web Summary" else "Text Summary"
                        val originalTextToSave = if (hasPdf || hasExtractedDoc) {
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
                    val failLog = formatLog("Error: ${error.message ?: "Unknown error"}")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            progressMessage = "",
                            errorMessage = error.message ?: "Summarization failed.",
                            liveLogs = it.liveLogs + failLog
                        )
                    }
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
