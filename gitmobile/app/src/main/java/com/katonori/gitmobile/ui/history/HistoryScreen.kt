package com.katonori.gitmobile.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.katonori.gitmobile.core.git.GitLogEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
    onOpenCommit: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("履歴") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is HistoryUiState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }
            is HistoryUiState.Error -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(padding).padding(16.dp))
            }
            is HistoryUiState.Content -> {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    items(state.entries, key = { it.commitId }) { entry ->
                        CommitRow(entry, onClick = { onOpenCommit(entry.commitId) })
                        HorizontalDivider()
                    }
                    if (!state.endReached) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (state.loadingMore) {
                                    CircularProgressIndicator()
                                } else {
                                    TextButton(onClick = { viewModel.loadMore() }) { Text("もっと読み込む") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitRow(entry: GitLogEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(entry.shortMessage, style = MaterialTheme.typography.bodyLarge)
        val date = Instant.ofEpochMilli(entry.commitTimeMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)
        Text(
            text = "${entry.shortId} · ${entry.authorName} · $date",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
