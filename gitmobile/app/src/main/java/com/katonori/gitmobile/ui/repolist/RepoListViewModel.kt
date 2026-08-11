package com.katonori.gitmobile.ui.repolist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katonori.gitmobile.core.data.LocalRepo
import com.katonori.gitmobile.core.data.LocalRepoStore
import com.katonori.gitmobile.core.git.GitRepositoryManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RepoListViewModel(
    private val localRepoStore: LocalRepoStore,
    private val gitRepositoryManager: GitRepositoryManager,
) : ViewModel() {

    val repos: StateFlow<List<LocalRepo>> = localRepoStore.repos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteRepo(repo: LocalRepo) {
        viewModelScope.launch {
            gitRepositoryManager.deleteLocalRepo(repo.id)
            localRepoStore.removeRepo(repo.id)
        }
    }
}
