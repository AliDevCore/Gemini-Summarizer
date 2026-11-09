package com.gemini.summarizer.presentation.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

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

    // Export File Launcher
    var pendingExportFormat by remember { mutableStateOf("txt") }
    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
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
            // Document Ingestion Banner & Action
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (uiState.selectedFileName != null) "File: ${uiState.selectedFileName}" else "Import Document",
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

            // Direct Text Input Field
            OutlinedTextField(
                value = uiState.inputText,
                onValueChange = { viewModel.onInputTextChanged(it) },
                label = { Text("Input Text") },
                placeholder = { Text("Type or paste long text, articles, or transcripts here...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 280.dp),
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

            // Progress Indicator
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
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = uiState.progressMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Summarize Button
            Button(
                onClick = { viewModel.startSummarization() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !uiState.isLoading && uiState.inputText.isNotBlank(),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Summarize with Gemini", style = MaterialTheme.typography.titleMedium)
            }

            // Results Section
            uiState.summaryResult?.let { result ->
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
                            text = "Input: ${result.inputWords} words -> Output: ${result.outputWords} words",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        HorizontalDivider()

                        SelectionContainer {
                            Text(
                                text = result.summaryText,
                                style = MaterialTheme.typography.bodyLarge,
                                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                            )
                        }

                        HorizontalDivider()

                        // Export Options
                        Text("Export Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    pendingExportFormat = "txt"
                                    exportFileLauncher.launch("Summary.txt")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("TXT")
                            }
                            OutlinedButton(
                                onClick = {
                                    pendingExportFormat = "md"
                                    exportFileLauncher.launch("Summary.md")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("MD")
                            }
                            OutlinedButton(
                                onClick = {
                                    pendingExportFormat = "pdf"
                                    exportFileLauncher.launch("Summary.pdf")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("PDF")
                            }
                            OutlinedButton(
                                onClick = {
                                    pendingExportFormat = "docx"
                                    exportFileLauncher.launch("Summary.docx")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("DOCX")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
