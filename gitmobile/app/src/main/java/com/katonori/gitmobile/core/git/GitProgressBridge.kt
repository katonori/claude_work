package com.katonori.gitmobile.core.git

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.jgit.lib.ProgressMonitor

/** Bridges JGit's callback-based [ProgressMonitor] to a [StateFlow] Compose can observe. */
class GitProgressBridge : ProgressMonitor {

    private val _progress = MutableStateFlow<GitProgress?>(null)
    val progress: StateFlow<GitProgress?> = _progress.asStateFlow()

    private var currentTitle = ""
    private var currentTotal = 0
    private var currentCompleted = 0

    @Volatile
    private var cancelled = false

    override fun start(totalTasks: Int) {
        // No-op: per-task progress is surfaced via beginTask/update instead.
    }

    override fun beginTask(title: String?, totalWork: Int) {
        currentTitle = title.orEmpty()
        currentTotal = totalWork
        currentCompleted = 0
        _progress.value = GitProgress(currentTitle, currentCompleted, currentTotal)
    }

    override fun update(completed: Int) {
        currentCompleted += completed
        _progress.value = GitProgress(currentTitle, currentCompleted, currentTotal)
    }

    override fun endTask() {
        _progress.value = null
    }

    override fun isCancelled(): Boolean = cancelled

    override fun showDuration(enabled: Boolean) {
        // Not surfaced in the UI for v1.
    }

    fun cancel() {
        cancelled = true
    }
}
