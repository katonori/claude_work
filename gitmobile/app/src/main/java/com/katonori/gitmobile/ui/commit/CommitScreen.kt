package com.katonori.gitmobile.ui.commit

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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitScreen(
    viewModel: CommitViewModel,
    onBack: () -> Unit,
    onCommitted: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is CommitEvent.Committed) onCommitted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("コミット") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is CommitUiState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }
            is CommitUiState.Error -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(padding).padding(16.dp))
            }
            is CommitUiState.Content -> {
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    if (state.files.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) { Text("変更はありません。") }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("変更ファイル (${state.files.size})", style = MaterialTheme.typography.titleSmall)
                            Row {
                                TextButton(onClick = { viewModel.setSelectAll(true) }) { Text("全選択") }
                                TextButton(onClick = { viewModel.setSelectAll(false) }) { Text("全解除") }
                            }
                        }
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            items(state.files, key = { it.path }) { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = file.selected,
                                        onCheckedChange = { viewModel.toggleSelection(file.path) },
                                    )
                                    Text(
                                        text = file.path,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (file.isDeletion) {
                                        Text("削除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.message,
                            onValueChange = viewModel::setMessage,
                            label = { Text("コミットメッセージ") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                        Button(
                            onClick = viewModel::commit,
                            enabled = !state.committing && state.message.isNotBlank() && state.files.any { it.selected },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.committing) "コミット中…" else "コミット")
                        }
                    }
                }
            }
        }
    }
}
