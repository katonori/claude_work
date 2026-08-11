package com.katonori.gitmobile.ui.clone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneScreen(
    viewModel: CloneViewModel,
    onBack: () -> Unit,
    onCloned: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var url by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var pat by remember { mutableStateOf("") }
    var savePat by remember { mutableStateOf(true) }

    LaunchedEffect(uiState) {
        val state = uiState
        if (state is CloneUiState.Success) onCloned(state.repoId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("リポジトリをクローン") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("リポジトリURL (https://...)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("表示名 (任意)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = pat,
                onValueChange = { pat = it },
                label = { Text("Personal Access Token (非公開リポジトリのみ)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = savePat, onCheckedChange = { savePat = it })
                Text("このホスト用にトークンを保存する")
            }

            when (val state = uiState) {
                is CloneUiState.Cloning -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val progress = state.progress
                        if (progress != null && progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { progress.completed.toFloat() / progress.total.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "${progress.taskTitle} (${progress.completed}/${progress.total})",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
                is CloneUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> Unit
            }

            Button(
                onClick = { viewModel.clone(url.trim(), displayName.trim(), pat.trim(), savePat) },
                enabled = uiState !is CloneUiState.Cloning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("クローン")
            }
        }
    }
}
