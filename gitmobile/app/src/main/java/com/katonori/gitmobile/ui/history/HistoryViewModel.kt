package com.katonori.gitmobile.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katonori.gitmobile.core.git.GitLogEntry
import com.katonori.gitmobile.core.git.GitRepositoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data class Content(val entries: List<GitLogEntry>, val loadingMore: Boolean, val endReached: Boolean) : HistoryUiState
    data class Error(val message: String) : HistoryUiState
}

class HistoryViewModel(
    private val repoId: String,
    private val gitRepositoryManager: GitRepositoryManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HistoryUiState>(HistoryUiState.Loading)
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        loadFirstPage()
    }

    fun loadFirstPage() {
        viewModelScope.launch {
            _uiState.value = HistoryUiState.Loading
            gitRepositoryManager.getLog(repoId, PAGE_SIZE, 0)
                .onSuccess { entries ->
                    _uiState.value = HistoryUiState.Content(entries, loadingMore = false, endReached = entries.size < PAGE_SIZE)
                }
                .onFailure { e -> _uiState.value = HistoryUiState.Error(e.message ?: "エラーが発生しました。") }
        }
    }

    fun loadMore() {
        val state = _uiState.value as? HistoryUiState.Content ?: return
        if (state.loadingMore || state.endReached) return
        viewModelScope.launch {
            _uiState.value = state.copy(loadingMore = true)
            gitRepositoryManager.getLog(repoId, PAGE_SIZE, state.entries.size)
                .onSuccess { more ->
                    _uiState.value = HistoryUiState.Content(
                        entries = state.entries + more,
                        loadingMore = false,
                        endReached = more.size < PAGE_SIZE,
                    )
                }
                .onFailure { _uiState.value = state.copy(loadingMore = false) }
        }
    }

    companion object {
        private const val PAGE_SIZE = 30
    }
}
