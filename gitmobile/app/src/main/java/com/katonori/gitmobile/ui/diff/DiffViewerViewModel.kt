package com.katonori.gitmobile.ui.diff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katonori.gitmobile.core.git.FileDiff
import com.katonori.gitmobile.core.git.GitRepositoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DiffUiState {
    data object Loading : DiffUiState
    data class Content(val diffs: List<FileDiff>) : DiffUiState
    data class Error(val message: String) : DiffUiState
}

/** Shows the working-tree diff (uncommitted changes vs HEAD) for a repo. */
class DiffViewerViewModel(
    private val repoId: String,
    private val gitRepositoryManager: GitRepositoryManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DiffUiState>(DiffUiState.Loading)
    val uiState: StateFlow<DiffUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = DiffUiState.Loading
            gitRepositoryManager.getWorkingTreeDiff(repoId)
                .onSuccess { diffs -> _uiState.value = DiffUiState.Content(diffs) }
                .onFailure { e -> _uiState.value = DiffUiState.Error(e.message ?: "エラーが発生しました。") }
        }
    }
}
