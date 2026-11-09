package com.gemini.summarizer.presentation.home

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gemini.summarizer.core.util.MarkdownRenderer

class DynamicCreateDocumentContract : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent {
        val (defaultName, mimeType) = input
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_TITLE, defaultName)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (intent == null || resultCode != Activity.RESULT_OK) null else intent.data
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // File Picker Launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.processFileUri(it) }
    }

    // Export File Launcher with exact MIME types to prevent accidental .txt suffixes
    var pendingExportFormat by remember { mutableStateOf("txt") }
    val dynamicExportLauncher = rememberLauncherForActivityResult(
        contract = DynamicCreateDocumentContract()
    ) { uri ->
        uri?.let { viewModel.exportSummary(it, pendingExportFormat) }
    }

    val wordCount = remember(uiState.inputText) {
        if (uiState.inputText.isBlank()) 0 else uiState.inputText.trim().split("\\s+".toRegex()).size
    }
    val charCount = uiState.inputText.length

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gemini Summarizer", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Document Selection & Active Attachment Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.selectedFileName != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (uiState.selectedFileName != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = uiState.selectedFileName!!,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Ready to transmit directly to Gemini",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.removeSelectedFile() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove file",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Attach Document",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Supports PDF, DOCX, TXT, Markdown, CSV",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                filePickerLauncher.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "text/*"
                                    )
                                )
                            }
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Browse")
                        }
                    }
                }
            }

            // Direct Text Input Field (Supports text or live web URLs!)
            OutlinedTextField(
                value = uiState.inputText,
                onValueChange = { viewModel.onInputTextChanged(it) },
                label = {
                    Text(
                        if (uiState.selectedFileName != null)
                            "Additional Instructions / Notes (Optional)"
                        else
                            "Input Text or Web URL"
                    )
                },
                placeholder = {
                    Text(
                        if (uiState.selectedFileName != null)
                            "Enter any special focus or instructions for the attached document..."
                        else
                            "Type/paste text, articles, or a web URL (e.g. https://...)"
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 240.dp),
                shape = MaterialTheme.shapes.medium
            )

            // Word and Char Counter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Words: $wordCount | Characters: $charCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                if (uiState.inputText.isNotEmpty()) {
                    TextButton(onClick = { viewModel.onClearInput() }) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
            }

            // Target Language Configuration
            Text("Target Language", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val languages = listOf("Auto", "English", "Persian", "Arabic", "Turkish", "German", "French", "Spanish")
                languages.forEach { lang ->
                    FilterChip(
                        selected = uiState.targetLanguage.equals(lang, ignoreCase = true),
                        onClick = { viewModel.onLanguageSelected(lang) },
                        label = { Text(lang) }
                    )
                }
            }

            // Summary Length Configuration
            Text("Summary Length", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val lengths = listOf("Very Short", "Short", "Medium", "Detailed")
                lengths.forEach { len ->
                    FilterChip(
                        selected = uiState.summaryLength.equals(len, ignoreCase = true),
                        onClick = { viewModel.onLengthSelected(len) },
                        label = { Text(len) }
                    )
                }
            }

            // Summary Type Configuration
            Text("Summary Format", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val types = listOf("General", "Bullet Points", "Executive Summary", "Key Takeaways", "Academic", "Chapter by Chapter")
                types.forEach { t ->
                    FilterChip(
                        selected = uiState.summaryType.equals(t, ignoreCase = true),
                        onClick = { viewModel.onTypeSelected(t) },
                        label = { Text(t) }
                    )
                }
            }

            // Error Display
            if (uiState.errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = uiState.errorMessage ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Progress Indicator & Cancel / Stop Button
            if (uiState.isLoading) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = uiState.progressMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.cancelSummarization() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.onError)
                            Spacer(Modifier.width(8.dp))
                            Text("Cancel / Stop Summarization", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onError)
                        }
                    }
                }
            }

            // Live Diagnostic Activity Log
            if (uiState.liveLogs.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Activity & Diagnostic Log",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (uiState.isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        uiState.liveLogs.takeLast(6).forEach { logLine ->
                            Text(
                                text = logLine,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Summarize Button
            if (!uiState.isLoading) {
                Button(
                    onClick = { viewModel.startSummarization() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = uiState.inputText.isNotBlank() || uiState.selectedFileName != null,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (uiState.selectedFileName != null) "Summarize Document" else "Summarize with Gemini",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            // Results Section
            uiState.summaryResult?.let { result ->
                val isRtlText = remember(result.summaryText, uiState.targetLanguage) {
                    uiState.targetLanguage.equals("persian", ignoreCase = true) ||
                    uiState.targetLanguage.equals("arabic", ignoreCase = true) ||
                    result.summaryText.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' }
                }

                val formattedSummary = remember(result.summaryText) {
                    MarkdownRenderer.parse(result.summaryText)
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Summary Result",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Gemini Summary", result.summaryText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Summary copied to clipboard", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                        }

                        // Metadata Badges
                        Text(
                            text = "Model: ${result.modelUsed} | Account: ${result.accountUsed} | Project: ${result.projectId} | Stages: ${result.stepsCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (result.inputWords > 0) "Input: ${result.inputWords} words -> Output: ${result.outputWords} words" else "Output: ${result.outputWords} words",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        HorizontalDivider()

                        // RTL or LTR aware display box with clean Markdown rendering
                        CompositionLocalProvider(
                            LocalLayoutDirection provides (if (isRtlText) LayoutDirection.Rtl else LayoutDirection.Ltr)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = formattedSummary,
                                    style = MaterialTheme.typography.bodyLarge,
                                    lineHeight = 26.sp,
                                    textAlign = if (isRtlText) TextAlign.Right else TextAlign.Left,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        HorizontalDivider()

                        // Export Options with stylish icons and proper MIME type launchers
                        Text("Export Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ExportIconButton(
                                icon = Icons.Default.PictureAsPdf,
                                label = "PDF",
                                onClick = {
                                    pendingExportFormat = "pdf"
                                    dynamicExportLauncher.launch(Pair("Summary.pdf", "application/pdf"))
                                }
                            )
                            ExportIconButton(
                                icon = Icons.Default.Description,
                                label = "Word",
                                onClick = {
                                    pendingExportFormat = "docx"
                                    dynamicExportLauncher.launch(
                                        Pair("Summary.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                                    )
                                }
                            )
                            ExportIconButton(
                                icon = Icons.Default.Article,
                                label = "Markdown",
                                onClick = {
                                    pendingExportFormat = "md"
                                    dynamicExportLauncher.launch(Pair("Summary.md", "text/markdown"))
                                }
                            )
                            ExportIconButton(
                                icon = Icons.Default.TextSnippet,
                                label = "Text",
                                onClick = {
                                    pendingExportFormat = "txt"
                                    dynamicExportLauncher.launch(Pair("Summary.txt", "text/plain"))
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun ExportIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(4.dp)
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(50.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}
