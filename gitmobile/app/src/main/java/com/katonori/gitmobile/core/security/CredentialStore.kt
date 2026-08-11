package com.katonori.gitmobile.core.security

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.credentialDataStore: DataStore<Preferences> by preferencesDataStore(name = "credentials")

/**
 * Stores a Personal Access Token per remote host, encrypted with [CryptoKeyProvider]. Tokens are
 * never logged and only held in memory for the duration of a single JGit call.
 */
class CredentialStore(
    private val context: Context,
    private val crypto: CryptoKeyProvider = CryptoKeyProvider(),
) {

    suspend fun savePat(host: String, token: String) {
        val payload = crypto.encrypt(token.toByteArray(Charsets.UTF_8))
        context.credentialDataStore.edit { prefs ->
            prefs[ivKey(host)] = Base64.encodeToString(payload.iv, Base64.NO_WRAP)
            prefs[cipherKey(host)] = Base64.encodeToString(payload.ciphertext, Base64.NO_WRAP)
        }
    }

    suspend fun getPat(host: String): String? {
        val prefs = context.credentialDataStore.data.first()
        val ivB64 = prefs[ivKey(host)] ?: return null
        val ctB64 = prefs[cipherKey(host)] ?: return null
        val payload = EncryptedPayload(
            iv = Base64.decode(ivB64, Base64.NO_WRAP),
            ciphertext = Base64.decode(ctB64, Base64.NO_WRAP),
        )
        return crypto.decrypt(payload).toString(Charsets.UTF_8)
    }

    suspend fun removePat(host: String) {
        context.credentialDataStore.edit { prefs ->
            prefs.remove(ivKey(host))
            prefs.remove(cipherKey(host))
        }
    }

    val savedHosts: Flow<Set<String>> = context.credentialDataStore.data.map { prefs ->
        prefs.asMap().keys
            .mapNotNull { key -> key.name.removePrefix(CIPHER_PREFIX).takeIf { key.name.startsWith(CIPHER_PREFIX) } }
            .toSet()
    }

    private fun ivKey(host: String) = stringPreferencesKey("$IV_PREFIX$host")
    private fun cipherKey(host: String) = stringPreferencesKey("$CIPHER_PREFIX$host")

    companion object {
        private const val IV_PREFIX = "iv_"
        private const val CIPHER_PREFIX = "ct_"

        /** Extracts the host portion (e.g. "github.com") from a git remote URL. */
        fun hostOf(remoteUrl: String): String =
            runCatching { java.net.URI(remoteUrl).host }.getOrNull() ?: remoteUrl
    }
}
