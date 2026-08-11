package com.katonori.gitmobile.ui.repolist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.katonori.gitmobile.core.data.LocalRepo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoListScreen(
    viewModel: RepoListViewModel,
    onAddRepo: () -> Unit,
    onOpenRepo: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val repos by viewModel.repos.collectAsState()
    var pendingDelete by remember { mutableStateOf<LocalRepo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitMobile") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "設定")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddRepo) {
                Icon(Icons.Filled.Add, contentDescription = "リポジトリを追加")
            }
        }
    ) { padding ->
        if (repos.isEmpty()) {
            EmptyState(padding)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(repos, key = { it.id }) { repo ->
                    RepoRow(
                        repo = repo,
                        onClick = { onOpenRepo(repo.id) },
                        onDeleteRequest = { pendingDelete = repo },
                    )
                }
            }
        }
    }

    pendingDelete?.let { repo ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("リポジトリを削除") },
            text = { Text("「${repo.name}」をこの端末から削除します。よろしいですか？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRepo(repo)
                    pendingDelete = null
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") }
            }
        )
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "リポジトリがありません。\n右下の + からクローンしてください。",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepoRow(repo: LocalRepo, onClick: () -> Unit, onDeleteRequest: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onDeleteRequest),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = repo.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = repo.remoteUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                repo.defaultBranch?.let {
                    Text(text = "ブランチ: $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onDeleteRequest) {
                Icon(Icons.Filled.Delete, contentDescription = "削除")
            }
        }
    }
}
