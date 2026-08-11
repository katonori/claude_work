package com.katonori.gitmobile.core.git

data class GitStatus(
    val added: Set<String>,
    val changed: Set<String>,
    val modified: Set<String>,
    val missing: Set<String>,
    val removed: Set<String>,
    val untracked: Set<String>,
    val conflicting: Set<String> = emptySet(),
) {
    val isClean: Boolean
        get() = added.isEmpty() && changed.isEmpty() && modified.isEmpty() &&
            missing.isEmpty() && removed.isEmpty() && untracked.isEmpty()

    /** Paths already in the index (staged). */
    val stagedPaths: Set<String> get() = added + changed + removed

    /** Paths not yet staged. */
    val unstagedPaths: Set<String> get() = modified + missing + untracked

    /** All changed paths, staged or not, deduplicated. */
    val allPaths: Set<String> get() = stagedPaths + unstagedPaths

    /** Subset of [allPaths] that must be staged via `git rm` rather than `git add`. */
    val deletionPaths: Set<String> get() = missing + removed
}

data class GitLogEntry(
    val commitId: String,
    val shortId: String,
    val shortMessage: String,
    val fullMessage: String,
    val authorName: String,
    val authorEmail: String,
    val commitTimeMillis: Long,
)

data class BranchInfo(
    val name: String,
    val isCurrent: Boolean,
    val isRemote: Boolean,
)

enum class DiffLineType { HEADER, CONTEXT, ADDED, REMOVED }

data class DiffLine(val type: DiffLineType, val text: String)

data class FileDiff(
    val oldPath: String,
    val newPath: String,
    val isBinary: Boolean,
    val lines: List<DiffLine>,
) {
    val displayPath: String get() = if (newPath != "/dev/null") newPath else oldPath
}

data class GitProgress(
    val taskTitle: String,
    val completed: Int,
    val total: Int,
)
