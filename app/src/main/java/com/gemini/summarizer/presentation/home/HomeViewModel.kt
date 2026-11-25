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
import com.gemini.summarizer.core.media.AppMediaType
import com.gemini.summarizer.core.media.MediaHelper
import com.gemini.summarizer.core.network.GeminiFileData
import com.gemini.summarizer.core.security.SecurityManager
import com.gemini.summarizer.domain.engine.AutoRotationEngine
import com.gemini.summarizer.domain.engine.HierarchicalSummarizer
import com.gemini.summarizer.domain.engine.MediaTranscriptionEngine
import com.gemini.summarizer.domain.engine.SummarizationFinalResult
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
    val selectedMediaType: AppMediaType = AppMediaType.NONE,
    val selectedMediaUri: Uri? = null,
    val selectedFileBase64: String? = null,
    val selectedFileText: String? = null,
    val selectedFileMimeType: String? = null,
    val extractedTranscript: String? = null,
    val isTranscribingSpeech: Boolean = false,
    val summaryResult: SummarizationFinalResult? = null,
    val targetLanguage: String = "Auto",
    val customPrompt: String = SummaryConfig.DEFAULT_PROMPT,
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
    private val transcriptionEngine = MediaTranscriptionEngine(application, database, securityManager)

    private var summarizationJob: Job? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private fun formatLog(msg: String): String = "[${timeFormat.format(Date())}] $msg"

    init {
        viewModelScope.launch {
            val defLang = settingsDataStore.defaultLanguageFlow.first()
            val savedPrompt = settingsDataStore.customPromptFlow.first()
            _uiState.update {
                it.copy(
                    targetLanguage = defLang,
                    customPrompt = savedPrompt
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

    fun onCustomPromptChanged(prompt: String) {
        _uiState.update { it.copy(customPrompt = prompt) }
        viewModelScope.launch {
            settingsDataStore.setCustomPrompt(prompt)
        }
    }

    fun onResetPromptToDefault() {
        _uiState.update { it.copy(customPrompt = SummaryConfig.DEFAULT_PROMPT) }
        viewModelScope.launch {
            settingsDataStore.setCustomPrompt(SummaryConfig.DEFAULT_PROMPT)
        }
    }

    fun onClearInput() {
        _uiState.update {
            it.copy(
                inputText = "",
                summaryResult = null,
                errorMessage = null,
                liveLogs = emptyList()
            )
        }
    }

    fun removeSelectedFile() {
        _uiState.update {
            it.copy(
                selectedFileName = null,
                selectedMediaType = AppMediaType.NONE,
                selectedMediaUri = null,
                selectedFileBase64 = null,
                selectedFileText = null,
                selectedFileMimeType = null,
                extractedTranscript = null,
                summaryResult = null,
                errorMessage = null,
                liveLogs = emptyList()
            )
        }
    }

    fun processFileUri(uri: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val mediaType = MediaHelper.getMediaTypeFromUri(context, uri)
            val fileName = MediaHelper.getFileNameFromUri(context, uri)

            if (mediaType == AppMediaType.AUDIO || mediaType == AppMediaType.VIDEO) {
                // Audio / Video Media File
                _uiState.update {
                    it.copy(
                        selectedFileName = fileName,
                        selectedMediaType = mediaType,
                        selectedMediaUri = uri,
                        selectedFileBase64 = null,
                        selectedFileText = null,
                        selectedFileMimeType = if (mediaType == AppMediaType.AUDIO) "audio/*" else "video/*",
                        extractedTranscript = null,
                        summaryResult = null,
                        errorMessage = null,
                        isLoading = false,
                        progressMessage = ""
                    )
                }
                val typeName = if (mediaType == AppMediaType.AUDIO) "Audio" else "Video"
                val log = formatLog("Selected $typeName file: $fileName. Ready to transcribe with Cloudflare Whisper.")
                _uiState.update { it.copy(liveLogs = it.liveLogs + log) }
                return@launch
            }

            // Document File (PDF, DOCX, TXT, etc.)
            _uiState.update {
                it.copy(
                    isLoading = true,
                    progressMessage = "Opening document...",
                    summaryResult = null,
                    liveLogs = emptyList()
                )
            }

            withContext(Dispatchers.IO) {
                try {
                    val extractResult = documentExtractor.extractFromUri(uri)
                    extractResult.fold(
                        onSuccess = { doc ->
                            if (doc.text.isBlank()) {
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        progressMessage = "",
                                        errorMessage = "Could not extract readable text from '${doc.fileName}'. It may be a scanned image or empty."
                                    )
                                }
                            } else {
                                _uiState.update {
                                    it.copy(
                                        selectedFileName = doc.fileName,
                                        selectedMediaType = AppMediaType.DOCUMENT,
                                        selectedMediaUri = null,
                                        selectedFileBase64 = null,
                                        selectedFileText = doc.text,
                                        selectedFileMimeType = "text/plain",
                                        extractedTranscript = null,
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
                                    errorMessage = "Failed to parse document: ${err.message}"
                                )
                            }
                        }
                    )
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

    fun extractTranscriptOnly() {
        val mediaUri = _uiState.value.selectedMediaUri
        val rawInput = _uiState.value.inputText.trim()
        val isMediaUrl = isWebUrl(rawInput) && MediaHelper.isMediaUrlCandidate(rawInput)

        if (mediaUri == null && !isMediaUrl) {
            _uiState.update { it.copy(errorMessage = "Please attach an audio/video file or enter a media link first.") }
            return
        }

        summarizationJob?.cancel()
        val startLog = formatLog("Starting speech-to-text extraction...")
        _uiState.update {
            it.copy(
                isLoading = true,
                isTranscribingSpeech = true,
                progressMessage = "Initializing Cloudflare Whisper STT...",
                errorMessage = null,
                liveLogs = listOf(startLog)
            )
        }

        summarizationJob = viewModelScope.launch {
            val whisperModel = settingsDataStore.whisperModelFlow.first()
            val whisperLang = _uiState.value.targetLanguage

            val transResult = if (mediaUri != null) {
                transcriptionEngine.transcribeMediaUri(
                    uri = mediaUri,
                    targetLanguage = whisperLang,
                    whisperModel = whisperModel,
                    onProgressUpdate = { progress ->
                        val log = formatLog(progress)
                        _uiState.update { it.copy(progressMessage = progress, liveLogs = it.liveLogs + log) }
                    }
                )
            } else {
                transcriptionEngine.transcribeMediaUrl(
                    url = rawInput,
                    targetLanguage = whisperLang,
                    whisperModel = whisperModel,
                    onProgressUpdate = { progress ->
                        val log = formatLog(progress)
                        _uiState.update { it.copy(progressMessage = progress, liveLogs = it.liveLogs + log) }
                    }
                )
            }

            transResult.fold(
                onSuccess = { res ->
                    val doneLog = formatLog("Speech transcription completed successfully (${res.totalWords} words).")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isTranscribingSpeech = false,
                            progressMessage = "",
                            extractedTranscript = res.transcriptText,
                            inputText = res.transcriptText,
                            liveLogs = it.liveLogs + doneLog
                        )
                    }
                },
                onFailure = { err ->
                    val errLog = formatLog("Transcription failed: ${err.message}")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isTranscribingSpeech = false,
                            progressMessage = "",
                            errorMessage = err.message ?: "Transcription failed.",
                            liveLogs = it.liveLogs + errLog
                        )
                    }
                }
            )
        }
    }

    fun cancelSummarization() {
        summarizationJob?.cancel()
        summarizationJob = null
        val cancelLog = formatLog("Process cancelled by user.")
        _uiState.update {
            it.copy(
                isLoading = false,
                isTranscribingSpeech = false,
                progressMessage = "",
                errorMessage = "Operation cancelled by user.",
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
        val hasMediaUri = _uiState.value.selectedMediaUri != null
        val hasPdf = _uiState.value.selectedFileBase64 != null
        val hasExtractedDoc = _uiState.value.selectedFileText != null
        val hasText = _uiState.value.inputText.isNotBlank()

        if (!hasMediaUri && !hasPdf && !hasExtractedDoc && !hasText) {
            _uiState.update { it.copy(errorMessage = "Please enter text, a web URL, or select a document/media file.") }
            return
        }

        summarizationJob?.cancel()
        val startLog = formatLog("Starting summarization process...")

        _uiState.update {
            it.copy(
                summaryResult = null,
                isLoading = true,
                isTranscribingSpeech = false,
                errorMessage = null,
                progressMessage = "Initializing AI engine...",
                liveLogs = listOf(startLog)
            )
        }

        val config = SummaryConfig(
            targetLanguage = _uiState.value.targetLanguage,
            customPrompt = _uiState.value.customPrompt.ifBlank { SummaryConfig.DEFAULT_PROMPT }
        )

        summarizationJob = viewModelScope.launch {
            val whisperModel = settingsDataStore.whisperModelFlow.first()
            val rawInput = _uiState.value.inputText.trim()

            // 1. Check if user attached an Audio or Video file
            if (hasMediaUri) {
                val mediaUri = _uiState.value.selectedMediaUri!!
                val fileName = _uiState.value.selectedFileName ?: "Media"

                val transLog = formatLog("Step 1/2: Transcribing speech from '$fileName' with Cloudflare Whisper...")
                _uiState.update {
                    it.copy(
                        progressMessage = "Transcribing speech from media file...",
                        liveLogs = it.liveLogs + transLog
                    )
                }

                val transResult = transcriptionEngine.transcribeMediaUri(
                    uri = mediaUri,
                    targetLanguage = _uiState.value.targetLanguage,
                    whisperModel = whisperModel,
                    onProgressUpdate = { progress ->
                        val log = formatLog(progress)
                        _uiState.update { it.copy(progressMessage = progress, liveLogs = it.liveLogs + log) }
                    }
                )

                if (transResult.isFailure) {
                    val err = transResult.exceptionOrNull()
                    val failLog = formatLog("Speech transcription failed: ${err?.message}")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            progressMessage = "",
                            errorMessage = err?.message ?: "Failed to transcribe audio from media file.",
                            liveLogs = it.liveLogs + failLog
                        )
                    }
                    return@launch
                }

                val transcriptionData = transResult.getOrThrow()
                _uiState.update { it.copy(extractedTranscript = transcriptionData.transcriptText) }

                val summaryLog = formatLog("Step 2/2: Summarizing extracted transcript (${transcriptionData.totalWords} words)...")
                _uiState.update {
                    it.copy(
                        progressMessage = "Summarizing transcript with AI...",
                        liveLogs = it.liveLogs + summaryLog
                    )
                }

                val userNotes = _uiState.value.inputText.ifBlank { null }
                val contentToSummarize = if (userNotes != null) {
                    "User Focus / Directives: $userNotes\n\nSpoken Dialogue Transcript:\n${transcriptionData.transcriptText}"
                } else {
                    transcriptionData.transcriptText
                }

                val summaryResult = hierarchicalSummarizer.summarizeText(
                    inputText = contentToSummarize,
                    config = config,
                    onProgressUpdate = { progress ->
                        val log = formatLog(progress)
                        _uiState.update { it.copy(progressMessage = progress, liveLogs = it.liveLogs + log) }
                    }
                )

                handleSummaryResult(
                    result = summaryResult,
                    config = config,
                    title = "[${if (_uiState.value.selectedMediaType == AppMediaType.VIDEO) "Video" else "Audio"}] $fileName",
                    originalText = transcriptionData.transcriptText
                )
                return@launch
            }

            // 2. Check if user provided an Audio/Video URL
            if (isWebUrl(rawInput)) {
                val mediaProbe = withContext(Dispatchers.IO) {
                    MediaHelper.probeUrlContentType(rawInput)
                }

                if (mediaProbe == AppMediaType.AUDIO || mediaProbe == AppMediaType.VIDEO) {
                    val transLog = formatLog("Step 1/2: Downloading and transcribing media from URL with Cloudflare Whisper...")
                    _uiState.update {
                        it.copy(
                            progressMessage = "Transcribing speech from media link...",
                            liveLogs = it.liveLogs + transLog
                        )
                    }

                    val transResult = transcriptionEngine.transcribeMediaUrl(
                        url = rawInput,
                        targetLanguage = _uiState.value.targetLanguage,
                        whisperModel = whisperModel,
                        onProgressUpdate = { progress ->
                            val log = formatLog(progress)
                            _uiState.update { it.copy(progressMessage = progress, liveLogs = it.liveLogs + log) }
                        }
                    )

                    if (transResult.isFailure) {
                        val err = transResult.exceptionOrNull()
                        val failLog = formatLog("Media transcription failed: ${err?.message}")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                progressMessage = "",
                                errorMessage = err?.message ?: "Failed to transcribe speech from media URL.",
                                liveLogs = it.liveLogs + failLog
                            )
                        }
                        return@launch
                    }

                    val transcriptionData = transResult.getOrThrow()
                    _uiState.update { it.copy(extractedTranscript = transcriptionData.transcriptText) }

                    val summaryLog = formatLog("Step 2/2: Summarizing extracted transcript (${transcriptionData.totalWords} words)...")
                    _uiState.update {
                        it.copy(
                            progressMessage = "Summarizing transcript with AI...",
                            liveLogs = it.liveLogs + summaryLog
                        )
                    }

                    val summaryResult = hierarchicalSummarizer.summarizeText(
                        inputText = transcriptionData.transcriptText,
                        config = config,
                        onProgressUpdate = { progress ->
                            val log = formatLog(progress)
                            _uiState.update { it.copy(progressMessage = progress, liveLogs = it.liveLogs + log) }
                        }
                    )

                    handleSummaryResult(
                        result = summaryResult,
                        config = config,
                        title = "[Media Link] ${transcriptionData.displayName}",
                        originalText = transcriptionData.transcriptText
                    )
                    return@launch
                }
            }

            // 3. Document (PDF inline or DOCX/TXT) or regular Web / Text
            val result = if (hasPdf) {
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
                            it.copy(progressMessage = progress, liveLogs = it.liveLogs + logEntry)
                        }
                    }
                )
            } else if (hasExtractedDoc) {
                val docText = _uiState.value.selectedFileText!!
                val notes = _uiState.value.inputText.ifBlank { null }
                val contentWithNotes = if (notes != null) "User Instructions: $notes\n\nDocument Content:\n$docText" else docText

                hierarchicalSummarizer.summarizeText(
                    inputText = contentWithNotes,
                    config = config,
                    onProgressUpdate = { progress ->
                        val logEntry = formatLog(progress)
                        _uiState.update {
                            it.copy(progressMessage = progress, liveLogs = it.liveLogs + logEntry)
                        }
                    }
                )
            } else {
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
                            it.copy(progressMessage = progress, liveLogs = it.liveLogs + logEntry)
                        }
                    }
                )
            }

            val title = _uiState.value.selectedFileName ?: if (isWebUrl(rawInput)) "Web Summary" else "Text Summary"
            handleSummaryResult(result, config, title, rawInput)
        }
    }

    private suspend fun handleSummaryResult(
        result: Result<SummarizationFinalResult>,
        config: SummaryConfig,
        title: String,
        originalText: String
    ) {
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
                    val originalTextToSave = if (saveOrig) originalText else "[Original text omitted per user settings]"

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
                            summaryType = "Custom Prompt"
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
