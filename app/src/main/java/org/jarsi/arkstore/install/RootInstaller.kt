package org.jarsi.arkstore.install

import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File
import java.io.IOException
import org.jarsi.arkstore.backup.AppBackup

/** What `pm` said of a command: whether it went through, and its reason when it did not. */
internal data class PmResult(val success: Boolean, val message: String?)

/** Reads what the package manager's shell commands print. */
internal object PmOutput {
    private val SESSION = Regex("""Success: created install session \[(\d{1,9})]""")
    private val FAILURE = Regex("""Failure \[([^\]]*)]""")

    /** The session `pm install-create` made, or null when it made none. */
    fun sessionId(text: String): Int? = SESSION.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The verdict in [text]: a line beginning "Success", or a "Failure [reason]". Null when
     * there is neither, as when the command was never run.
     */
    fun result(text: String): PmResult? {
        FAILURE.find(text)?.let { return PmResult(false, it.groupValues[1].trim().ifEmpty { null }) }
        if (text.lineSequence().any { it.trimStart().startsWith("Success") }) return PmResult(true, null)
        return null
    }

    /** The status the system installer would have reported for the failure [message]. */
    fun status(message: String?): Int = when {
        message == null -> PackageInstaller.STATUS_FAILURE
        message.startsWith("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
            message.startsWith("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE") ||
            message.contains("signature", ignoreCase = true) -> PackageInstaller.STATUS_FAILURE_CONFLICT
        message.startsWith("INSTALL_FAILED_OLDER_SDK") ||
            message.startsWith("INSTALL_FAILED_NEWER_SDK") ||
            message.startsWith("INSTALL_FAILED_NO_MATCHING_ABIS") ||
            message.startsWith("INSTALL_FAILED_MISSING_SHARED_LIBRARY") -> PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
        message.startsWith("INSTALL_PARSE_FAILED") ||
            message.startsWith("INSTALL_FAILED_INVALID_APK") -> PackageInstaller.STATUS_FAILURE_INVALID
        message.startsWith("INSTALL_FAILED_INSUFFICIENT_STORAGE") -> PackageInstaller.STATUS_FAILURE_STORAGE
        else -> PackageInstaller.STATUS_FAILURE
    }
}

/**
 * The commands given to `pm`, made of nothing but package names and file names that pass
 * the checks of a backup, and numbers.
 */
internal object RootCommands {
    fun createSession(installer: String, bytes: Long): String {
        require(AppBackup.isPackageName(installer)) { "Not a package name" }
        require(bytes > 0) { "No size" }
        return "pm install-create -r --user current -i $installer -S $bytes"
    }

    fun writeSplit(sessionId: Int, name: String, bytes: Long): String {
        require(AppBackup.isFileName(name)) { "Not a file name" }
        require(bytes >= 0) { "No size" }
        return "pm install-write -S $bytes $sessionId $name -"
    }

    fun commit(sessionId: Int): String = "pm install-commit $sessionId"

    fun abandon(sessionId: Int): String = "pm install-abandon $sessionId"

    fun uninstall(packageName: String): String {
        require(AppBackup.isPackageName(packageName)) { "Not a package name" }
        return "pm uninstall --user current $packageName"
    }
}

/**
 * Installs and uninstalls through `pm` as root, so that nothing is asked of the user. The
 * store is named as the installer, so that its updates go without a prompt even once root is
 * gone again.
 */
internal object RootInstaller {
    private const val TAG = "RootInstaller"

    /** A session's commands have a limit each; a commit of a large app can take a while. */
    private const val PM_TIMEOUT_MS = 10 * 60 * 1000L

    /** How the install came out: a verdict, in the system's own statuses, or no root to try with. */
    sealed interface Result {
        data class Done(val status: Int, val message: String?) : Result
        data object NoRoot : Result
    }

    /**
     * Installs [files] as one session, the base file first, [installer] named as the one who
     * did; [onSession] is told the session's id before anything is written to it. Any file
     * that cannot be given whole fails the session, which is then abandoned.
     */
    suspend fun install(
        installer: String,
        files: List<Pair<String, File>>,
        onSession: (Int) -> Unit
    ): Result {
        val total = files.sumOf { it.second.length() }
        val created = Root.run(RootCommands.createSession(installer, total), PM_TIMEOUT_MS)
        if (created !is Root.Outcome.Ran) return Result.NoRoot
        val sessionId = PmOutput.sessionId(created.out) ?: return verdict(created, "install-create")
        onSession(sessionId)
        try {
            for ((name, file) in files) {
                val written = Root.run(RootCommands.writeSplit(sessionId, name, file.length()), PM_TIMEOUT_MS, stdin = { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                })
                val result = (written as? Root.Outcome.Ran)?.let { PmOutput.result(it.out) }
                if (result?.success != true) {
                    abandon(sessionId)
                    return if (written is Root.Outcome.Ran) verdict(written, "install-write") else Result.NoRoot
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "A file could not be given to the session", e)
            abandon(sessionId)
            return Result.Done(PackageInstaller.STATUS_FAILURE, e.message)
        }
        val committed = Root.run(RootCommands.commit(sessionId), PM_TIMEOUT_MS)
        if (committed !is Root.Outcome.Ran) {
            abandon(sessionId)
            return Result.NoRoot
        }
        return verdict(committed, "install-commit")
    }

    suspend fun uninstall(packageName: String): Result {
        val ran = Root.run(RootCommands.uninstall(packageName), PM_TIMEOUT_MS)
        if (ran !is Root.Outcome.Ran) return Result.NoRoot
        return verdict(ran, "uninstall")
    }

    private suspend fun abandon(sessionId: Int) {
        Root.run(RootCommands.abandon(sessionId), PM_TIMEOUT_MS)
    }

    /** What [ran] came to; a command that said neither way failed, with whatever it did say. */
    private fun verdict(ran: Root.Outcome.Ran, step: String): Result.Done {
        val result = PmOutput.result(ran.out)
        if (result == null) {
            Log.w(TAG, "pm $step said nothing of its outcome (exit ${ran.exit}): ${ran.out.take(200)} ${ran.err.take(200)}")
            val said = ran.err.ifBlank { ran.out }.trim().lines().firstOrNull()?.take(200)
            return Result.Done(PackageInstaller.STATUS_FAILURE, said?.ifEmpty { null })
        }
        return if (result.success) {
            Result.Done(PackageInstaller.STATUS_SUCCESS, null)
        } else {
            Result.Done(PmOutput.status(result.message), result.message)
        }
    }
}
