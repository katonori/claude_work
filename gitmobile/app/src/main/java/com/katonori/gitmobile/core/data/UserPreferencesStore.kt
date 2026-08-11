package com.katonori.gitmobile.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.userPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

data class UserIdentity(val name: String, val email: String)

/** Stores the commit author identity (name/email) used for local commits. */
class UserPreferencesStore(private val context: Context) {

    val identity: Flow<UserIdentity> = context.userPrefsDataStore.data.map { prefs ->
        UserIdentity(
            name = prefs[NAME_KEY] ?: DEFAULT_NAME,
            email = prefs[EMAIL_KEY] ?: DEFAULT_EMAIL,
        )
    }

    suspend fun setIdentity(name: String, email: String) {
        context.userPrefsDataStore.edit { prefs ->
            prefs[NAME_KEY] = name
            prefs[EMAIL_KEY] = email
        }
    }

    companion object {
        private val NAME_KEY = stringPreferencesKey("user_name")
        private val EMAIL_KEY = stringPreferencesKey("user_email")
        const val DEFAULT_NAME = "GitMobile User"
        const val DEFAULT_EMAIL = "user@example.com"
    }
}
