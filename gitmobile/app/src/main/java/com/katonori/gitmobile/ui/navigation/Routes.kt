package com.katonori.gitmobile.ui.navigation

object Routes {
    const val REPO_LIST = "repo_list"
    const val CLONE = "clone"
    const val SETTINGS = "settings"

    const val DASHBOARD = "dashboard/{repoId}"
    const val COMMIT = "commit/{repoId}"
    const val BRANCHES = "branches/{repoId}"
    const val HISTORY = "history/{repoId}"
    const val COMMIT_DETAIL = "commit_detail/{repoId}/{commitId}"
    const val DIFF_WORKING = "diff/{repoId}"
    const val DIFF_COMMIT = "diff/{repoId}/{commitId}"

    fun dashboard(repoId: String) = "dashboard/$repoId"
    fun commit(repoId: String) = "commit/$repoId"
    fun branches(repoId: String) = "branches/$repoId"
    fun history(repoId: String) = "history/$repoId"
    fun commitDetail(repoId: String, commitId: String) = "commit_detail/$repoId/$commitId"
    fun diffWorking(repoId: String) = "diff/$repoId"
    fun diffCommit(repoId: String, commitId: String) = "diff/$repoId/$commitId"
}
