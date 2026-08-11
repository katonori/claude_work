package com.katonori.gitmobile.ui.clone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katonori.gitmobile.core.data.LocalRepo
import com.katonori.gitmobile.core.data.LocalRepoStore
import com.katonori.gitmobile.core.git.GitProgress
import com.katonori.gitmobile.core.git.GitProgressBridge
import com.katonori.gitmobile.core.git.GitRepositoryManager
import com.katonori.gitmobile.core.security.CredentialStore
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

sealed interface CloneUiState {
    data object Idle : CloneUiState
    data class Cloning(val progress: GitProgress?) : CloneUiState
    data class Success(val repoId: String) : CloneUiState
    data class Error(val message: String) : CloneUiState
}

class CloneViewModel(
    private val gitRepositoryManager: GitRepositoryManager,
    private val localRepoStore: LocalRepoStore,
    private val credentialStore: CredentialStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CloneUiState>(CloneUiState.Idle)
    val uiState: StateFlow<CloneUiState> = _uiState.asStateFlow()

    fun clone(remoteUrl: String, displayName: String, pat: String, savePatForHost: Boolean) {
        if (_uiState.value is CloneUiState.Cloning) return
        if (remoteUrl.isBlank()) {
            _uiState.value = CloneUiState.Error("リポジトリのURLを入力してください。")
            return
        }
        viewModelScope.launch {
            val progressBridge = GitProgressBridge()
            val progressJob = launch {
                progressBridge.progress.collect { p -> _uiState.value = CloneUiState.Cloning(p) }
            }
            _uiState.value = CloneUiState.Cloning(null)

            val repoId = UUID.randomUUID().toString()
            val credentialsProvider = if (pat.isNotBlank()) {
                UsernamePasswordCredentialsProvider(pat, "")
            } else {
                null
            }

            val result = gitRepositoryManager.cloneRepository(repoId, remoteUrl, credentialsProvider, progressBridge)
            progressJob.cancel()

            result.onSuccess { dir ->
                val name = displayName.ifBlank {
                    remoteUrl.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
                }
                val currentBranch = gitRepositoryManager.getCurrentBranch(repoId).getOrNull()
                localRepoStore.addRepo(
                    LocalRepo(
                        id = repoId,
                        name = name,
                        localPath = dir.absolutePath,
                        remoteUrl = remoteUrl,
                        defaultBranch = currentBranch,
                        lastOpenedAt = System.currentTimeMillis(),
                    )
                )
                if (savePatForHost && pat.isNotBlank()) {
                    credentialStore.savePat(CredentialStore.hostOf(remoteUrl), pat)
                }
                _uiState.value = CloneUiState.Success(repoId)
            }.onFailure { e ->
                _uiState.value = CloneUiState.Error(e.message ?: "クローンに失敗しました。")
            }
        }
    }

    fun resetError() {
        _uiState.value = CloneUiState.Idle
    }
}
