package com.gemini.summarizer.presentation.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(viewModel: AccountsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var inputKey by remember { mutableStateOf("") }
    var inputLabel by remember { mutableStateOf("") }
    var inputProjectId by remember { mutableStateOf("") }

    LaunchedEffect(uiState.showAddKeyDialog) {
        if (!uiState.showAddKeyDialog) {
            inputKey = ""
            inputLabel = ""
            inputProjectId = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloudflare API Keys", fontWeight = FontWeight.Bold) }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    inputKey = ""
                    inputLabel = ""
                    inputProjectId = ""
                    viewModel.openAddKeyDialog()
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add Cloudflare Key", fontWeight = FontWeight.SemiBold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // General Error Banner on Main Screen
            if (uiState.errorMessage != null && !uiState.showAddKeyDialog) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.errorMessage ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearErrorMessage() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (uiState.keysList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CloudQueue,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "No Cloudflare Keys Added Yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Tap the 'Add Cloudflare Key' button below to add your Cloudflare API Token and Account ID. You can add multiple keys for automatic rotation and load balancing.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.keysList, key = { it.id }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.VpnKey,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = item.label,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "Account: ${item.projectId}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = item.maskedKey,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Status: ${item.statusMessage}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (item.isInvalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { viewModel.testApiKey(item) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Sync,
                                            contentDescription = "Test Key",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Switch(
                                        checked = item.isActive,
                                        onCheckedChange = { viewModel.toggleKeyActive(item) }
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(onClick = { viewModel.deleteKey(item) }) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "Delete Key",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(90.dp)) }
                }
            }
        }

        // Add API Key Dialog
        if (uiState.showAddKeyDialog) {
            AlertDialog(
                onDismissRequest = { if (!uiState.isLoading) viewModel.dismissAddKeyDialog() },
                title = { Text("Add Cloudflare API Key") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Enter your Cloudflare API Token (with Workers AI permissions) and your Account ID from the Cloudflare Dashboard:",
                            style = MaterialTheme.typography.bodySmall
                        )

                        uiState.errorMessage?.let { errorMsg ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = errorMsg,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = inputKey,
                            onValueChange = { inputKey = it },
                            label = { Text("Cloudflare API Token") },
                            placeholder = { Text("Bearer token from Cloudflare") },
                            singleLine = true,
                            enabled = !uiState.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = inputProjectId,
                            onValueChange = { inputProjectId = it },
                            label = { Text("Cloudflare Account ID (Required)") },
                            placeholder = { Text("32-character hex ID") },
                            singleLine = true,
                            enabled = !uiState.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = inputLabel,
                            onValueChange = { inputLabel = it },
                            label = { Text("Label (Optional, e.g. Primary Account)") },
                            singleLine = true,
                            enabled = !uiState.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.addDirectApiKey(inputKey, inputLabel, inputProjectId)
                        },
                        enabled = inputKey.isNotBlank() && inputProjectId.isNotBlank() && !uiState.isLoading
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Save Key")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissAddKeyDialog() },
                        enabled = !uiState.isLoading
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Delete Confirmation Dialog
        uiState.keyPendingDelete?.let { pendingItem ->
            AlertDialog(
                onDismissRequest = { viewModel.cancelDeleteKey() },
                title = { Text("Delete API Key") },
                text = { Text("Are you sure you want to delete '${pendingItem.label}' (${pendingItem.maskedKey})? This action cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = { viewModel.confirmDeleteKey() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.cancelDeleteKey() }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
