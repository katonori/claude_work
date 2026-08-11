package com.katonori.gitmobile.core.data

import org.json.JSONObject

data class LocalRepo(
    val id: String,
    val name: String,
    val localPath: String,
    val remoteUrl: String,
    val defaultBranch: String? = null,
    val lastOpenedAt: Long = 0L,
)

internal fun LocalRepo.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("localPath", localPath)
    put("remoteUrl", remoteUrl)
    put("defaultBranch", defaultBranch ?: JSONObject.NULL)
    put("lastOpenedAt", lastOpenedAt)
}

internal fun JSONObject.toLocalRepo(): LocalRepo = LocalRepo(
    id = getString("id"),
    name = getString("name"),
    localPath = getString("localPath"),
    remoteUrl = getString("remoteUrl"),
    defaultBranch = if (isNull("defaultBranch")) null else getString("defaultBranch"),
    lastOpenedAt = optLong("lastOpenedAt", 0L),
)
