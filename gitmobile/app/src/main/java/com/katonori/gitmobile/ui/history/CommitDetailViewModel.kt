package com.katonori.gitmobile.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katonori.gitmobile.core.git.FileDiff
import com.katonori.gitmobile.core.git.GitLogEntry
import com.katonori.gitmobile.core.git.GitRepositoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CommitDetailUiState {
    data object Loading : CommitDetailUiState
    data class Content(val entry: GitLogEntry, val diffs: List<FileDiff>) : CommitDetailUiState
    data class Error(val message: String) : CommitDetailUiState
}

class CommitDetailViewModel(
    private val repoId: String,
    private val commitId: String,
    private val gitRepositoryManager: GitRepositoryManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CommitDetailUiState>(CommitDetailUiState.Loading)
    val uiState: StateFlow<CommitDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = CommitDetailUiState.Loading
            val entry = gitRepositoryManager.getCommit(repoId, commitId).getOrElse {
                _uiState.value = CommitDetailUiState.Error(it.message ?: "エラーが発生しました。")
                return@launch
            }
            val diffs = gitRepositoryManager.getCommitDiff(repoId, commitId).getOrElse {
                _uiState.value = CommitDetailUiState.Error(it.message ?: "エラーが発生しました。")
                return@launch
            }
            _uiState.value = CommitDetailUiState.Content(entry, diffs)
        }
    }
}
