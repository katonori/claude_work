package com.katonori.gitmobile.ui.commit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katonori.gitmobile.core.data.UserPreferencesStore
import com.katonori.gitmobile.core.git.GitRepositoryManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class CommitFileItem(val path: String, val isDeletion: Boolean, val selected: Boolean)

sealed interface CommitUiState {
    data object Loading : CommitUiState
    data class Content(val files: List<CommitFileItem>, val message: String, val committing: Boolean) : CommitUiState
    data class Error(val message: String) : CommitUiState
}

sealed interface CommitEvent {
    data object Committed : CommitEvent
    data class Error(val message: String) : CommitEvent
}

class CommitViewModel(
    private val repoId: String,
    private val gitRepositoryManager: GitRepositoryManager,
    private val userPreferencesStore: UserPreferencesStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CommitUiState>(CommitUiState.Loading)
    val uiState: StateFlow<CommitUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CommitEvent>()
    val events: SharedFlow<CommitEvent> = _events.asSharedFlow()

    init {
        loadStatus()
    }

    fun loadStatus() {
        viewModelScope.launch {
            _uiState.value = CommitUiState.Loading
            gitRepositoryManager.getStatus(repoId)
                .onSuccess { status ->
                    val deletions = status.deletionPaths
                    val files = status.allPaths.sorted().map { path ->
                        CommitFileItem(path = path, isDeletion = path in deletions, selected = true)
                    }
                    _uiState.value = CommitUiState.Content(files, message = "", committing = false)
                }
                .onFailure { e -> _uiState.value = CommitUiState.Error(e.message ?: "エラーが発生しました。") }
        }
    }

    fun toggleSelection(path: String) {
        val state = _uiState.value as? CommitUiState.Content ?: return
        _uiState.value = state.copy(
            files = state.files.map { if (it.path == path) it.copy(selected = !it.selected) else it }
        )
    }

    fun setSelectAll(selected: Boolean) {
        val state = _uiState.value as? CommitUiState.Content ?: return
        _uiState.value = state.copy(files = state.files.map { it.copy(selected = selected) })
    }

    fun setMessage(message: String) {
        val state = _uiState.value as? CommitUiState.Content ?: return
        _uiState.value = state.copy(message = message)
    }

    fun commit() {
        val state = _uiState.value as? CommitUiState.Content ?: return
        val selectedPaths = state.files.filter { it.selected }.map { it.path }.toSet()
        if (selectedPaths.isEmpty() || state.message.isBlank()) return
        val deletionPaths = state.files.filter { it.isDeletion }.map { it.path }.toSet()

        viewModelScope.launch {
            _uiState.value = state.copy(committing = true)
            val identity = userPreferencesStore.identity.first()
            gitRepositoryManager.stageAndCommit(
                repoId, selectedPaths, deletionPaths, state.message, identity.name, identity.email,
            ).onSuccess {
                _events.emit(CommitEvent.Committed)
            }.onFailure { e ->
                (_uiState.value as? CommitUiState.Content)?.let { _uiState.value = it.copy(committing = false) }
                _events.emit(CommitEvent.Error(e.message ?: "コミットに失敗しました。"))
            }
        }
    }
}
