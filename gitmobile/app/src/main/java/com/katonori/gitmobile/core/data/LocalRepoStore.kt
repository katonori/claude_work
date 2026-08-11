package com.katonori.gitmobile.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.repoDataStore: DataStore<Preferences> by preferencesDataStore(name = "local_repos")

/** Persists the list of repositories cloned onto this device. */
class LocalRepoStore(private val context: Context) {

    val repos: Flow<List<LocalRepo>> = context.repoDataStore.data.map { prefs -> decode(prefs[REPOS_KEY]) }

    suspend fun addRepo(repo: LocalRepo) {
        context.repoDataStore.edit { prefs ->
            val current = decode(prefs[REPOS_KEY]).filterNot { it.id == repo.id }
            prefs[REPOS_KEY] = encode(current + repo)
        }
    }

    suspend fun removeRepo(id: String) {
        context.repoDataStore.edit { prefs ->
            val current = decode(prefs[REPOS_KEY]).filterNot { it.id == id }
            prefs[REPOS_KEY] = encode(current)
        }
    }

    suspend fun touch(id: String) {
        context.repoDataStore.edit { prefs ->
            val current = decode(prefs[REPOS_KEY]).map {
                if (it.id == id) it.copy(lastOpenedAt = System.currentTimeMillis()) else it
            }
            prefs[REPOS_KEY] = encode(current)
        }
    }

    suspend fun getById(id: String): LocalRepo? =
        decode(context.repoDataStore.data.first()[REPOS_KEY]).find { it.id == id }

    private fun encode(list: List<LocalRepo>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    private fun decode(raw: String?): List<LocalRepo> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getJSONObject(it).toLocalRepo() }
    }

    companion object {
        private val REPOS_KEY = stringPreferencesKey("repos_json")
    }
}
