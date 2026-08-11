package com.katonori.gitmobile.core.git

import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.TransportException

sealed class GitOperationException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class AuthenticationFailed(cause: Throwable? = null) :
        GitOperationException("認証に失敗しました。アクセストークンを確認してください。", cause)

    class NetworkError(cause: Throwable? = null) :
        GitOperationException("ネットワークエラーが発生しました。接続を確認して再試行してください。", cause)

    class InvalidRemote(cause: Throwable? = null) :
        GitOperationException("リポジトリのURLが正しくありません。", cause)

    class UncommittedChangesBlock(cause: Throwable? = null) :
        GitOperationException("未コミットの変更があるため操作できません。先にコミットまたは変更を破棄してください。", cause)

    class NonFastForward(cause: Throwable? = null) :
        GitOperationException("このバージョンではマージが必要な変更には対応していません。", cause)

    class Unknown(cause: Throwable) :
        GitOperationException(cause.message ?: "不明なエラーが発生しました。", cause)

    companion object {
        fun from(t: Throwable): GitOperationException {
            if (t is GitOperationException) return t
            return when (t) {
                is TransportException -> {
                    val msg = t.message.orEmpty()
                    if (msg.contains("not authorized", ignoreCase = true) ||
                        msg.contains("authentication", ignoreCase = true) ||
                        msg.contains("403", ignoreCase = true) ||
                        msg.contains("401", ignoreCase = true)
                    ) {
                        AuthenticationFailed(t)
                    } else {
                        NetworkError(t)
                    }
                }
                is InvalidRemoteException -> InvalidRemote(t)
                is CheckoutConflictException -> UncommittedChangesBlock(t)
                else -> Unknown(t)
            }
        }
    }
}
