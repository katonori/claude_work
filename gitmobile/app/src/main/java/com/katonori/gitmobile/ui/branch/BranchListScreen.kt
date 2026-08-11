package com.katonori.gitmobile.ui.branch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchListScreen(viewModel: BranchListViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var showNewBranchDialog by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var blockedMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is BranchEvent.Switched -> Unit
                is BranchEvent.UncommittedChangesBlocked ->
                    blockedMessage = "未コミットの変更があるため「${event.branchName}」に切り替えられません。先にコミットまたは変更を破棄してください。"
                is BranchEvent.Error -> errorMessage = event.message
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ブランチ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { showNewBranchDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新規ブランチ")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is BranchListUiState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }
            is BranchListUiState.Error -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(padding).padding(16.dp))
            }
            is BranchListUiState.Content -> {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    items(state.branches, key = { "${it.isRemote}/${it.name}" }) { branch ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.switching && !branch.isRemote) { viewModel.checkout(branch.name) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(branch.name, style = MaterialTheme.typography.bodyLarge)
                                if (branch.isRemote) {
                                    Text("リモート追跡ブランチ", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (branch.isCurrent) {
                                Icon(Icons.Filled.Check, contentDescription = "現在のブランチ", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewBranchDialog) {
        AlertDialog(
            onDismissRequest = { showNewBranchDialog = false },
            title = { Text("新規ブランチ") },
            text = {
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = { Text("ブランチ名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createBranch(newBranchName.trim())
                    newBranchName = ""
                    showNewBranchDialog = false
                }) { Text("作成") }
            },
            dismissButton = {
                TextButton(onClick = { showNewBranchDialog = false }) { Text("キャンセル") }
            }
        )
    }

    blockedMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { blockedMessage = null },
            title = { Text("切り替えできません") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { blockedMessage = null }) { Text("OK") } }
        )
    }

    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("エラー") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } }
        )
    }
}
