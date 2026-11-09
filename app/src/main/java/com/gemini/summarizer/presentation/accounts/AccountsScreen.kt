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
import com.gemini.summarizer.core.database.entity.AccountEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(viewModel: AccountsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var newAccountName by remember { mutableStateOf("") }

    var newApiKey by remember { mutableStateOf("") }
    var newProjectId by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts & API Keys", fontWeight = FontWeight.Bold) }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    newAccountName = ""
                    viewModel.openAddAccountDialog()
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add Account") }
            )
        }
    ) { padding ->
        if (uiState.accountsWithKeys.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "No Google Accounts Registered",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Add multiple independent Google AI Studio accounts and API keys to unlock auto-rotation and avoid quota bottlenecks.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(uiState.accountsWithKeys, key = { it.account.id }) { item ->
                    AccountCard(
                        accountWithKeys = item,
                        onToggleAccount = { viewModel.toggleAccountEnabled(item.account) },
                        onDeleteAccount = { viewModel.deleteAccount(item.account) },
                        onAddKey = { viewModel.openAddKeyDialog(item.account) },
                        onToggleKeyActive = { viewModel.toggleKeyActive(it) },
                        onDeleteKey = { viewModel.deleteKey(it) },
                        getMaskedKey = { viewModel.getMaskedKey(it) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        // Add Account Dialog
        if (uiState.showAddAccountDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissAddAccountDialog() },
                title = { Text("Add Google Account") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Give this account a distinctive label (e.g. Personal Account, Work Account):")
                        OutlinedTextField(
                            value = newAccountName,
                            onValueChange = { newAccountName = it },
                            label = { Text("Account Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.createAccount(newAccountName) },
                        enabled = newAccountName.isNotBlank()
                    ) {
                        Text("Save Account")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissAddAccountDialog() }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Add API Key Dialog
        uiState.showAddKeyDialogForAccount?.let { account ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissAddKeyDialog() },
                title = { Text("Add API Key to ${account.name}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Enter the Gemini API Key generated from Google AI Studio:")
                        OutlinedTextField(
                            value = newApiKey,
                            onValueChange = { newApiKey = it },
                            label = { Text("API Key (e.g. AIzaSy...)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newProjectId,
                            onValueChange = { newProjectId = it },
                            label = { Text("Google Cloud Project ID (Optional)") },
                            placeholder = { Text("e.g. my-gemini-project-123") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Specifying the Project ID ensures keys from the same project share quota limits, preventing wasted requests.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.addApiKey(newApiKey, newProjectId)
                            newApiKey = ""
                            newProjectId = ""
                        },
                        enabled = newApiKey.isNotBlank()
                    ) {
                        Text("Add Key")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.dismissAddKeyDialog()
                        newApiKey = ""
                        newProjectId = ""
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun AccountCard(
    accountWithKeys: AccountWithKeys,
    onToggleAccount: () -> Unit,
    onDeleteAccount: () -> Unit,
    onAddKey: () -> Unit,
    onToggleKeyActive: (com.gemini.summarizer.core.database.entity.ApiKeyEntity) -> Unit,
    onDeleteKey: (com.gemini.summarizer.core.database.entity.ApiKeyEntity) -> Unit,
    getMaskedKey: (com.gemini.summarizer.core.database.entity.ApiKeyEntity) -> String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = accountWithKeys.account.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = accountWithKeys.account.isEnabled,
                        onCheckedChange = { onToggleAccount() }
                    )
                    IconButton(onClick = onDeleteAccount) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Account", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Keys list
            if (accountWithKeys.keys.isEmpty()) {
                Text(
                    text = "No API keys added yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    accountWithKeys.keys.forEach { key ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = getMaskedKey(key),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Project: ${key.projectId} | Status: ${key.statusMessage}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (key.isInvalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = key.isActive,
                                        onCheckedChange = { onToggleKeyActive(key) }
                                    )
                                    IconButton(onClick = { onDeleteKey(key) }) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove Key", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onAddKey,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add API Key")
            }
        }
    }
}
