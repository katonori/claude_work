package com.katonori.gitmobile.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katonori.gitmobile.core.data.LocalRepo
import com.katonori.gitmobile.core.data.LocalRepoStore
import com.katonori.gitmobile.core.git.GitProgress
import com.katonori.gitmobile.core.git.GitProgressBridge
import com.katonori.gitmobile.core.git.GitRepositoryManager
import com.katonori.gitmobile.core.git.GitStatus
import com.katonori.gitmobile.core.security.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data class Content(val repo: LocalRepo, val currentBranch: String, val status: GitStatus) : DashboardUiState
    data class Error(val message: String) : DashboardUiState
}

sealed interface SyncState {
    data object Idle : SyncState
    data class InProgress(val label: String, val progress: GitProgress?) : SyncState
    data class Done(val message: String) : SyncState
    data class Failed(val message: String) : SyncState
}

class RepoDashboardViewModel(
    private val repoId: String,
    private val gitRepositoryManager: GitRepositoryManager,
    private val localRepoStore: LocalRepoStore,
    private val credentialStore: CredentialStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val repo = localRepoStore.getById(repoId)
            if (repo == null) {
                _uiState.value = DashboardUiState.Error("リポジトリが見つかりません。")
                return@launch
            }
            localRepoStore.touch(repoId)
            val branchResult = gitRepositoryManager.getCurrentBranch(repoId)
            val statusResult = gitRepositoryManager.getStatus(repoId)
            val branch = branchResult.getOrElse {
                _uiState.value = DashboardUiState.Error(it.message ?: "エラーが発生しました。")
                return@launch
            }
            val status = statusResult.getOrElse {
                _uiState.value = DashboardUiState.Error(it.message ?: "エラーが発生しました。")
                return@launch
            }
            _uiState.value = DashboardUiState.Content(repo, branch, status)
        }
    }

    private suspend fun credentialsFor(remoteUrl: String): UsernamePasswordCredentialsProvider? {
        val pat = credentialStore.getPat(CredentialStore.hostOf(remoteUrl)) ?: return null
        return UsernamePasswordCredentialsProvider(pat, "")
    }

    fun pull() {
        viewModelScope.launch {
            val repo = localRepoStore.getById(repoId) ?: return@launch
            val cp = credentialsFor(repo.remoteUrl)
            val bridge = GitProgressBridge()
            val job = launch { bridge.progress.collect { p -> _syncState.value = SyncState.InProgress("Pull", p) } }
            _syncState.value = SyncState.InProgress("Pull", null)
            val result = gitRepositoryManager.pull(repoId, cp, bridge)
            job.cancel()
            result.onSuccess {
                _syncState.value = SyncState.Done("Pullが完了しました。")
                refresh()
            }.onFailure { e -> _syncState.value = SyncState.Failed(e.message ?: "Pullに失敗しました。") }
        }
    }

    fun push() {
        viewModelScope.launch {
            val repo = localRepoStore.getById(repoId) ?: return@launch
            val cp = credentialsFor(repo.remoteUrl)
            val bridge = GitProgressBridge()
            val job = launch { bridge.progress.collect { p -> _syncState.value = SyncState.InProgress("Push", p) } }
            _syncState.value = SyncState.InProgress("Push", null)
            val result = gitRepositoryManager.push(repoId, cp, bridge)
            job.cancel()
            result.onSuccess {
                _syncState.value = SyncState.Done("Pushが完了しました。")
                refresh()
            }.onFailure { e -> _syncState.value = SyncState.Failed(e.message ?: "Pushに失敗しました。") }
        }
    }

    fun dismissSyncMessage() {
        _syncState.value = SyncState.Idle
    }
}
