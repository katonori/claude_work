package com.katonori.gitmobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katonori.gitmobile.core.data.UserPreferencesStore
import com.katonori.gitmobile.core.security.CredentialStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val name: String = "",
    val email: String = "",
    val savedHosts: List<String> = emptyList(),
)

class SettingsViewModel(
    private val userPreferencesStore: UserPreferencesStore,
    private val credentialStore: CredentialStore,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        userPreferencesStore.identity,
        credentialStore.savedHosts,
    ) { identity, hosts -> SettingsUiState(identity.name, identity.email, hosts.sorted()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun saveIdentity(name: String, email: String) {
        viewModelScope.launch { userPreferencesStore.setIdentity(name, email) }
    }

    fun removeHostCredential(host: String) {
        viewModelScope.launch { credentialStore.removePat(host) }
    }
}
