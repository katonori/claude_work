package com.katonori.gitmobile.core.git

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand.ListMode
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator

/**
 * Wraps JGit for all git operations used by GitMobile. Every repository lives under
 * [reposRootDir]/<repoId>/ and all mutating operations on a given repo are serialized through a
 * per-repo [Mutex] to avoid concurrent JGit calls corrupting the working tree.
 */
class GitRepositoryManager(private val reposRootDir: File) {

    private val repoMutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(repoId: String): Mutex = repoMutexes.getOrPut(repoId) { Mutex() }

    fun repoDir(repoId: String): File = File(reposRootDir, repoId)

    private fun openGit(repoId: String): Git = Git.open(repoDir(repoId))

    suspend fun cloneRepository(
        repoId: String,
        remoteUrl: String,
        credentialsProvider: CredentialsProvider?,
        progressMonitor: ProgressMonitor = NullProgressMonitor.INSTANCE,
    ): Result<File> = withContext(Dispatchers.IO) {
        val dir = repoDir(repoId)
        try {
            if (dir.exists()) dir.deleteRecursively()
            reposRootDir.mkdirs()
            val cmd = Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(dir)
                .setProgressMonitor(progressMonitor)
            credentialsProvider?.let { cmd.setCredentialsProvider(it) }
            cmd.call().close()
            Result.success(dir)
        } catch (e: Exception) {
            dir.deleteRecursively()
            Result.failure(GitOperationException.from(e))
        }
    }

    suspend fun deleteLocalRepo(repoId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            repoDir(repoId).deleteRecursively()
            repoMutexes.remove(repoId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(GitOperationException.from(e))
        }
    }

    suspend fun getCurrentBranch(repoId: String): Result<String> = withContext(Dispatchers.IO) {
        mutexFor(repoId).withLock {
            try {
                openGit(repoId).use { git -> Result.success(git.repository.branch.orEmpty()) }
            } catch (e: Exception) {
                Result.failure(GitOperationException.from(e))
            }
        }
    }

    suspend fun getStatus(repoId: String): Result<GitStatus> = withContext(Dispatchers.IO) {
        mutexFor(repoId).withLock {
            try {
                openGit(repoId).use { git ->
                    val s = git.status().call()
                    Result.success(
                        GitStatus(
                            added = s.added,
                            changed = s.changed,
                            modified = s.modified,
                            missing = s.missing,
                            removed = s.removed,
                            untracked = s.untracked,
                            conflicting = s.conflicting,
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(GitOperationException.from(e))
            }
        }
    }

    suspend fun stageAndCommit(
        repoId: String,
        selectedPaths: Set<String>,
        deletionPaths: Set<String>,
        message: String,
        authorName: String,
        authorEmail: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        mutexFor(repoId).withLock {
            try {
                openGit(repoId).use { git ->
                    val addPaths = selectedPaths - deletionPaths
                    val rmPaths = selectedPaths intersect deletionPaths
                    if (addPaths.isNotEmpty()) {
                        val addCmd = git.add()
                        addPaths.forEach { addCmd.addFilepattern(it) }
                        addCmd.call()
                    }
                    if (rmPaths.isNotEmpty()) {
                        val rmCmd = git.rm()
                        rmPaths.forEach { rmCmd.addFilepattern(it) }
                        rmCmd.call()
                    }
                    val ident = PersonIdent(authorName, authorEmail)
                    val commit = git.commit()
                        .setMessage(message)
                        .setAuthor(ident)
                        .setCommitter(ident)
                        .call()
                    Result.success(commit.name)
                }
            } catch (e: Exception) {
                Result.failure(GitOperationException.from(e))
            }
        }
    }

    suspend fun pull(
        repoId: String,
        credentialsProvider: CredentialsProvider?,
        progressMonitor: ProgressMonitor = NullProgressMonitor.INSTANCE,
    ): Result<String> = withContext(Dispatchers.IO) {
        mutexFor(repoId).withLock {
            try {
                openGit(repoId).use { git ->
                    val cmd = git.pull().setProgressMonitor(progressMonitor)
                    credentialsProvider?.let { cmd.setCredentialsProvider(it) }
                    val result = cmd.call()
                    if (result.isSuccessful) {
                        val head = git.repository.resolve("HEAD")
                        Result.success(head?.name.orEmpty())
                    } else {
                        Result.failure(GitOperationException.NonFastForward())
                    }
                }
            } catch (e: Exception) {
                Result.failure(GitOperationException.from(e))
            }
        }
    }

    suspend fun push(
        repoId: String,
        credentialsProvider: CredentialsProvider?,
        progressMonitor: ProgressMonitor = NullProgressMonitor.INSTANCE,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutexFor(repoId).withLock {
            try {
                openGit(repoId).use { git ->
                    val cmd = git.push().setProgressMonitor(progressMonitor)
                    credentialsProvider?.let { cmd.setCredentialsProvider(it) }
                    val results = cmd.call()
                    val rejected = mutableListOf<String>()
                    results.forEach { pushResult ->
                        pushResult.remoteUpdates.forEach { update ->
                            if (update.status != RemoteRefUpdate.Status.OK &&
                                update.status != RemoteRefUpdate.Status.UP_TO_DATE
                            ) {
                                rejected += "${update.remoteName}: ${update.status}"
                            }
                        }
                    }
                    if (rejected.isEmpty()) {
                        Result.success(Unit)
                    } else {
                        Result.failure(GitOperationException.NonFastForward())
                    }
                }
            } catch (e: Exception) {
                Result.failure(GitOperationException.from(e))
            }
        }
    }

    suspend fun listBranches(repoId: String): Result<List<BranchInfo>> = withContext(Dispatchers.IO) {
        mutexFor(repoId).withLock {
            try {
                openGit(repoId).use { git ->
                    val current = git.repository.branch
                    val refs = git.branchList().setListMode(ListMode.ALL).call()
                    val branches = refs.map { ref ->
                        val shortName = Repository.shortenRefName(ref.name)
                        BranchInfo(
                            name = shortName,
                            isCurrent = !ref.name.startsWith("refs/remotes/") && shortName == current,
                            isRemote = ref.name.startsWith("refs/remotes/"),
                        )
                    }
                    Result.success(branches)
                }
            } catch (e: Exception) {
                Result.failure(GitOperationException.from(e))
            }
        }
    }

    suspend fun checkoutBranch(repoId: String, branchName: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutexFor(repoId).withLock {
            try {
                openGit(repoId).use { git ->
                    git.checkout().setName(branchName).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(GitOperationException.from(e))
            }
        }
    }

    suspend fun createBranch(
        repoId: String,
        newBranchName: String,
        startPoint: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutexFor(repoId).withLock {
            try {
                openGit(repoId).use { git ->
                    val cmd = git.branchCreate().setName(newBranchName)
                    startPoint?.let { cmd.setStartPoint(it) }
                    cmd.call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(GitOperationException.from(e))
            }
        }
    }

    suspend fun getCommit(repoId: String, commitId: String): Result<GitLogEntry> = withContext(Dispatchers.IO) {
        mutexFor(repoId).withLock {
            try {
                openGit(repoId).use { git ->
                    RevWalk(git.repository).use { walk ->
                        val c = walk.parseCommit(git.repository.resolve(commitId))
                        Result.success(
                            GitLogEntry(
                                commitId = c.name,
                                shortId = c.name.take(7),
                                shortMessage = c.shortMessage,
                                fullMessage = c.fullMessage,
                                authorName = c.authorIdent.name,
                                authorEmail = c.authorIdent.emailAddress,
                                commitTimeMillis = c.commitTime.toLong() * 1000L,
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Result.failure(GitOperationException.from(e))
            }
        }
    }

    suspend fun getLog(
        repoId: String,
        maxCount: Int,
        skip: Int = 0,
    ): Result<List<GitLogEntry>> = withContext(Dispatchers.IO) {
        mutexFor(repoId).withLock {
            try {
                openGit(repoId).use { git ->
                    val commits = git.log().setMaxCount(maxCount).setSkip(skip).call()
                    val entries = commits.map { c ->
                        GitLogEntry(
                            commitId = c.name,
                            shortId = c.name.take(7),
                            shortMessage = c.shortMessage,
                            fullMessage = c.fullMessage,
                            authorName = c.authorIdent.name,
                            authorEmail = c.authorIdent.emailAddress,
                            commitTimeMillis = c.commitTime.toLong() * 1000L,
                        )
                    }
                    Result.success(entries)
                }
            } catch (e: Exception) {
                Result.failure(GitOperationException.from(e))
            }
        }
    }

    suspend fun getWorkingTreeDiff(repoId: String): Result<List<FileDiff>> = withContext(Dispatchers.IO) {
        mutexFor(repoId).withLock {
            try {
                openGit(repoId).use { git ->
                    val repo = git.repository
                    val headId = repo.resolve("HEAD")
                    val oldTree: AbstractTreeIterator =
                        if (headId != null) prepareTreeParser(repo, headId.name) else EmptyTreeIterator()
                    val newTree = FileTreeIterator(repo)
                    Result.success(formatDiff(repo, oldTree, newTree))
                }
            } catch (e: Exception) {
                Result.failure(GitOperationException.from(e))
            }
        }
    }

    suspend fun getCommitDiff(repoId: String, commitId: String): Result<List<FileDiff>> = withContext(Dispatchers.IO) {
        mutexFor(repoId).withLock {
            try {
                openGit(repoId).use { git ->
                    val repo = git.repository
                    RevWalk(repo).use { walk ->
                        val commit = walk.parseCommit(repo.resolve(commitId))
                        val newTree = prepareTreeParser(repo, commit.name)
                        val oldTree: AbstractTreeIterator = if (commit.parentCount > 0) {
                            prepareTreeParser(repo, commit.getParent(0).name)
                        } else {
                            EmptyTreeIterator()
                        }
                        Result.success(formatDiff(repo, oldTree, newTree))
                    }
                }
            } catch (e: Exception) {
                Result.failure(GitOperationException.from(e))
            }
        }
    }

    private fun prepareTreeParser(repo: Repository, revString: String): CanonicalTreeParser {
        RevWalk(repo).use { walk ->
            val commit = walk.parseCommit(repo.resolve(revString))
            val treeId = commit.tree.id
            repo.newObjectReader().use { reader ->
                return CanonicalTreeParser().apply { reset(reader, treeId) }
            }
        }
    }

    private fun formatDiff(
        repo: Repository,
        oldTree: AbstractTreeIterator,
        newTree: AbstractTreeIterator,
    ): List<FileDiff> {
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { formatter ->
            formatter.setRepository(repo)
            formatter.setDetectRenames(true)
            val entries = formatter.scan(oldTree, newTree)
            return entries.map { entry ->
                out.reset()
                formatter.format(entry)
                val text = out.toString(Charsets.UTF_8.name())
                val isBinary = text.contains("Binary files")
                FileDiff(
                    oldPath = entry.oldPath,
                    newPath = entry.newPath,
                    isBinary = isBinary,
                    lines = if (isBinary) emptyList() else parseUnifiedDiff(text),
                )
            }
        }
    }

    private fun parseUnifiedDiff(text: String): List<DiffLine> =
        text.lineSequence().filter { it.isNotEmpty() }.map { line ->
            val type = when {
                line.startsWith("+++") || line.startsWith("---") ||
                    line.startsWith("diff ") || line.startsWith("index ") -> DiffLineType.HEADER
                line.startsWith("@@") -> DiffLineType.HEADER
                line.startsWith("+") -> DiffLineType.ADDED
                line.startsWith("-") -> DiffLineType.REMOVED
                else -> DiffLineType.CONTEXT
            }
            DiffLine(type, line)
        }.toList()
}
