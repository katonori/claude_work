package com.katonori.gitmobile.di

import android.content.Context
import com.katonori.gitmobile.core.data.LocalRepoStore
import com.katonori.gitmobile.core.data.UserPreferencesStore
import com.katonori.gitmobile.core.git.GitRepositoryManager
import com.katonori.gitmobile.core.security.CredentialStore
import java.io.File

/** Manual dependency container. A single instance lives on [com.katonori.gitmobile.GitMobileApp]. */
class AppContainer(context: Context) {

    val gitRepositoryManager: GitRepositoryManager by lazy {
        GitRepositoryManager(File(context.filesDir, "repos"))
    }

    val localRepoStore: LocalRepoStore by lazy { LocalRepoStore(context) }

    val credentialStore: CredentialStore by lazy { CredentialStore(context) }

    val userPreferencesStore: UserPreferencesStore by lazy { UserPreferencesStore(context) }
}
