package com.katonori.gitmobile.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDashboardScreen(
    viewModel: RepoDashboardViewModel,
    onBack: () -> Unit,
    onOpenCommit: () -> Unit,
    onOpenBranches: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenWorkingDiff: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val syncState by viewModel.syncState.collectAsState()

    LaunchedEffect(syncState) {
        if (syncState is SyncState.Done || syncState is SyncState.Failed) {
            kotlinx.coroutines.delay(3000)
            viewModel.dismissSyncMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState is DashboardUiState.Content) (uiState as DashboardUiState.Content).repo.name else "リポジトリ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is DashboardUiState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }
            is DashboardUiState.Error -> {
                Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is DashboardUiState.Content -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("ブランチ: ${state.currentBranch}", style = MaterialTheme.typography.titleMedium)
                            Text(state.repo.remoteUrl, style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = if (state.status.isClean) "変更なし" else "${state.status.allPaths.size} 件の変更 (${state.status.stagedPaths.size} ステージ済み)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    val syncing = syncState is SyncState.InProgress
                    when (val s = syncState) {
                        is SyncState.InProgress -> {
                            val p = s.progress
                            Text(
                                text = if (p != null) "${s.label}: ${p.taskTitle} (${p.completed}/${p.total})" else "${s.label} 中…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        is SyncState.Done -> Text(s.message, color = MaterialTheme.colorScheme.primary)
                        is SyncState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                        else -> Unit
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { viewModel.pull() }, enabled = !syncing, modifier = Modifier.weight(1f)) {
                            Text("Pull")
                        }
                        Button(onClick = { viewModel.push() }, enabled = !syncing, modifier = Modifier.weight(1f)) {
                            Text("Push")
                        }
                    }

                    OutlinedButton(onClick = onOpenCommit, modifier = Modifier.fillMaxWidth()) { Text("コミット") }
                    OutlinedButton(onClick = onOpenWorkingDiff, modifier = Modifier.fillMaxWidth()) { Text("変更を表示 (Diff)") }
                    OutlinedButton(onClick = onOpenBranches, modifier = Modifier.fillMaxWidth()) { Text("ブランチ") }
                    OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) { Text("履歴") }
                }
            }
        }
    }
}
