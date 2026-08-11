package com.katonori.gitmobile.ui.branch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katonori.gitmobile.core.git.BranchInfo
import com.katonori.gitmobile.core.git.GitOperationException
import com.katonori.gitmobile.core.git.GitRepositoryManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BranchListUiState {
    data object Loading : BranchListUiState
    data class Content(val branches: List<BranchInfo>, val switching: Boolean) : BranchListUiState
    data class Error(val message: String) : BranchListUiState
}

sealed interface BranchEvent {
    data object Switched : BranchEvent
    data class UncommittedChangesBlocked(val branchName: String) : BranchEvent
    data class Error(val message: String) : BranchEvent
}

class BranchListViewModel(
    private val repoId: String,
    private val gitRepositoryManager: GitRepositoryManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BranchListUiState>(BranchListUiState.Loading)
    val uiState: StateFlow<BranchListUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<BranchEvent>()
    val events: SharedFlow<BranchEvent> = _events.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = BranchListUiState.Loading
            gitRepositoryManager.listBranches(repoId)
                .onSuccess { branches -> _uiState.value = BranchListUiState.Content(branches, switching = false) }
                .onFailure { e -> _uiState.value = BranchListUiState.Error(e.message ?: "エラーが発生しました。") }
        }
    }

    fun checkout(branchName: String) {
        val state = _uiState.value as? BranchListUiState.Content ?: return
        viewModelScope.launch {
            _uiState.value = state.copy(switching = true)
            gitRepositoryManager.checkoutBranch(repoId, branchName)
                .onSuccess {
                    _events.emit(BranchEvent.Switched)
                    load()
                }
                .onFailure { e ->
                    _uiState.value = state.copy(switching = false)
                    if (e is GitOperationException.UncommittedChangesBlock) {
                        _events.emit(BranchEvent.UncommittedChangesBlocked(branchName))
                    } else {
                        _events.emit(BranchEvent.Error(e.message ?: "ブランチの切り替えに失敗しました。"))
                    }
                }
        }
    }

    fun createBranch(name: String) {
        val state = _uiState.value as? BranchListUiState.Content ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            _uiState.value = state.copy(switching = true)
            gitRepositoryManager.createBranch(repoId, name)
                .onSuccess {
                    gitRepositoryManager.checkoutBranch(repoId, name)
                    _events.emit(BranchEvent.Switched)
                    load()
                }
                .onFailure { e ->
                    _uiState.value = state.copy(switching = false)
                    _events.emit(BranchEvent.Error(e.message ?: "ブランチの作成に失敗しました。"))
                }
        }
    }
}
