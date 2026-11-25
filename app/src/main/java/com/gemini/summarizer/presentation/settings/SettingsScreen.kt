package com.gemini.summarizer.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    var showClearKeysDialog by remember { mutableStateOf(false) }
    var showClearAllDataDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) }
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
            // Notification Message
            uiState.message?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Summarization Defaults
            Text("Default Summarization Preferences", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Default Output Language", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Auto", "English", "Persian", "Arabic", "Turkish", "German", "French").forEach { lang ->
                            FilterChip(
                                selected = uiState.defaultLanguage == lang,
                                onClick = { viewModel.setDefaultLanguage(lang) },
                                label = { Text(lang) }
                            )
                        }
                    }
                }
            }

            // Cloudflare Whisper STT Configuration
            Text("Speech-to-Text (Cloudflare Whisper)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Whisper Model for Audio & Video Transcription", style = MaterialTheme.typography.titleSmall)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val whisperModels = listOf(
                            "@cf/openai/whisper-large-v3-turbo" to "Whisper Large v3 Turbo (⚡ Recommended • Fast & Accurate)",
                            "@cf/openai/whisper" to "Whisper Base (@cf/openai/whisper)"
                        )
                        whisperModels.forEach { (modelId, label) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = uiState.whisperModel == modelId,
                                    onClick = { viewModel.setWhisperModel(modelId) }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (uiState.whisperModel == modelId) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = modelId,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    Text(
                        text = "Cloudflare Workers AI grants 10,000 free daily Neurons per account (~214 minutes of speech transcription). Multi-account auto-rotation seamlessly switches accounts when quota is exhausted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // History Preferences
            Text("History & Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Record summary history locally",
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = uiState.saveHistory,
                            onCheckedChange = { viewModel.setSaveHistory(it) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Store original input text with summary",
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = uiState.saveOriginalText,
                            onCheckedChange = { viewModel.setSaveOriginalText(it) },
                            enabled = uiState.saveHistory
                        )
                    }
                }
            }

            // Privacy & Keystore Security
            Text("Privacy & Security", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Keys are securely encrypted using Android Keystore (AES-256-GCM). No user text or keys are ever transmitted to any third-party server besides official Cloudflare Workers AI endpoints.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = { showClearKeysDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.VpnKeyOff, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Purge All Stored API Keys")
                    }

                    Button(
                        onClick = { showClearAllDataDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clear All App Data & History")
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }

        // Dialogs
        if (showClearKeysDialog) {
            AlertDialog(
                onDismissRequest = { showClearKeysDialog = false },
                title = { Text("Purge All API Keys?") },
                text = { Text("All encrypted API keys will be deleted immediately from Keystore.") },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.clearAllSecureKeys()
                            showClearKeysDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Purge Keys")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearKeysDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showClearAllDataDialog) {
            AlertDialog(
                onDismissRequest = { showClearAllDataDialog = false },
                title = { Text("Reset Entire Application?") },
                text = { Text("This will permanently delete all API keys, history, and preferences.") },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.clearAllData()
                            showClearAllDataDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Reset Everything")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllDataDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
