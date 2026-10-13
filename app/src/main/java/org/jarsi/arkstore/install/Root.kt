package org.jarsi.arkstore.install

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread

/** What asking for root came to: granted, refused by the user or the root app, or no `su` at all. */
sealed interface RootCheck {
    /** Root was given; [provider] names the root app and its version when `su` tells it. */
    data class Granted(val provider: String?) : RootCheck
    data object Denied : RootCheck
    data object Missing : RootCheck
}

/**
 * Runs commands as root through the device's `su`, the one Magisk, KernelSU or APatch
 * provides, when the user has turned root on in the settings. Nothing here reaches `su`
 * unless the setting is on, and every command is put together from constants, numbers and
 * names that have passed their checks: no path and no name of a file read from a backup
 * ever reaches a command line as it is.
 */
object Root {
    private const val TAG = "Root"
    private const val PREFS = "root"
    private const val PREF_ENABLED = "enabled"
    private const val PREF_LOST = "lost"
    private const val MAX_PROVIDER = 40

    /**
     * The most of a command's text that is kept: a result of pm's is a line or two, and a
     * listing of every app's data a few hundred kilobytes at most. What goes over is not
     * read, and the outcome says so, see [Outcome.Ran.cut].
     */
    internal const val MAX_TEXT = 4 * 1024 * 1024

    /** The root app asks the user before answering; its prompt waits a while for them. */
    const val CHECK_TIMEOUT_MS = 30_000L

    private const val PER_USER_RANGE = 100_000

    /**
     * The Android user the store runs as: the one whose apps its commands install, remove
     * and stop, and under whose folders their data lives. A work or a private profile is a
     * user of its own, and not the one the device shows as current.
     */
    val userId: Int get() = android.os.Process.myUid() / PER_USER_RANGE

    /** How the command came out: it ran, whatever its exit code, or `su` could not be run at all. */
    sealed interface Outcome {
        /**
         * [out] and [err] are what the command printed, [cut] when [out] was more than is
         * kept, so that it is not the whole of what the command said.
         */
        data class Ran(val exit: Int, val out: String, val err: String, val cut: Boolean = false) : Outcome
        data object NoRoot : Outcome
    }

    /** Stands in for `su` in tests, given every command in place of a process; null outside them. */
    internal var transport: (
        suspend (command: String, stdin: ((OutputStream) -> Unit)?, stdout: ((InputStream) -> Unit)?, global: Boolean) -> Outcome
    )? = null

    /** Whether the user has turned root on. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(PREF_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit {
        putBoolean(PREF_ENABLED, on)
        if (!on) remove(PREF_LOST)
    }

    /** Whether the last install that asked for root did not get it, and went the ordinary way. */
    fun lost(context: Context): Boolean = prefs(context).getBoolean(PREF_LOST, false)

    fun markLost(context: Context, lost: Boolean) = prefs(context).edit { putBoolean(PREF_LOST, lost) }

    /** Asks `su` who it is, which makes the root app ask the user the first time. */
    suspend fun check(): RootCheck {
        val id = run("id", CHECK_TIMEOUT_MS)
        return when {
            id is Outcome.Ran && isRoot(id.out) -> RootCheck.Granted(version())
            id is Outcome.Ran -> RootCheck.Denied
            else -> if (hasSu()) RootCheck.Denied else RootCheck.Missing
        }
    }

    /**
     * Whether the device has a `su` at all, without asking it for anything: a root app
     * prints the usage of `su` without asking the user, so the settings can tell a device
     * that is not rooted from one that is before root is turned on.
     */
    suspend fun present(): Boolean = hasSu()

    /** Whether a `su` can be started at all, whatever it then says. */
    private suspend fun hasSu(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "--help").redirectErrorStream(true).start()
            process.outputStream.close()
            process.inputStream.close()
            process.destroy()
            true
        } catch (_: IOException) {
            false
        }
    }

    private suspend fun version(): String? = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "-v").redirectErrorStream(true).start()
            process.outputStream.close()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            providerName(text).ifEmpty { null }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Runs [command] as root. [stdin] may write to the command's input, which is closed after;
     * [stdout] may read its output, which is otherwise gathered into [Outcome.Ran.out]. A cancel
     * of the coroutine kills the command; so does [timeoutMs] running out, which counts as
     * [Outcome.NoRoot] (null for no limit, for a stream that takes as long as it takes).
     * A [global] command runs in the system's own mount namespace rather than the store's,
     * where Android shows an app nothing of the other apps' data folders; Magisk, KernelSU
     * and APatch all know `--mount-master` for it. Whatever the way out, [stdin] and [stdout]
     * have returned by the time this has: nothing of theirs goes on in the background.
     */
    suspend fun run(
        command: String,
        timeoutMs: Long?,
        stdin: ((OutputStream) -> Unit)? = null,
        stdout: ((InputStream) -> Unit)? = null,
        global: Boolean = false
    ): Outcome {
        transport?.let { return it(command, stdin, stdout, global) }
        return withContext(Dispatchers.IO) {
            val process = try {
                val args = if (global) listOf("su", "--mount-master", "-c", command) else listOf("su", "-c", command)
                ProcessBuilder(args).start()
            } catch (e: IOException) {
                Log.i(TAG, "su could not be started: ${e.message}")
                return@withContext Outcome.NoRoot
            }
            converse(process, command, timeoutMs, stdin, stdout)
        }
    }

    /**
     * Talks to [process], started for [command], as [run] says, and sees to it that the
     * process and the threads that feed and read it are gone before returning, however the
     * talk ended.
     */
    internal suspend fun converse(
        process: Process,
        command: String,
        timeoutMs: Long?,
        stdin: ((OutputStream) -> Unit)?,
        stdout: ((InputStream) -> Unit)?
    ): Outcome {
        val readers = ArrayList<Thread>(2)
        try {
            val ran = if (timeoutMs == null) {
                talk(process, stdin, stdout, readers)
            } else {
                withTimeoutOrNull(timeoutMs) { talk(process, stdin, stdout, readers) }
            }
            return if (ran == null) {
                Log.w(TAG, "su took too long and was killed: ${command.take(40)}")
                Outcome.NoRoot
            } else {
                if (ran.exit != 0) Log.i(TAG, "su -c ${command.take(40)} ended with ${ran.exit}: ${ran.err.trim().take(120)}")
                ran
            }
        } finally {
            process.destroy()
            // Once the process is gone its output ends, and the thread reading it, which may
            // be in the middle of writing into the caller's stream, is waited for: the caller
            // closes that stream next, and nothing may be writing into it then.
            for (reader in readers) {
                while (reader.isAlive) {
                    try {
                        reader.join()
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
    }

    /**
     * Feeds [process] its input and takes its output and errors, each on a thread of its own,
     * so that neither side fills up and blocks the other; the threads are put in [readers]
     * for the caller to wait for. A cancel kills the process, which ends the reads and writes
     * with an error that is swallowed here.
     */
    private suspend fun talk(
        process: Process,
        stdin: ((OutputStream) -> Unit)?,
        stdout: ((InputStream) -> Unit)?,
        readers: MutableList<Thread>
    ): Outcome.Ran = coroutineScope {
        val killer = launch {
            try {
                awaitCancellation()
            } finally {
                process.destroy()
            }
        }
        try {
            runInterruptible {
                val err = Gathered()
                val errReader = thread(name = "su-err") { gather(process.errorStream, err) }
                readers += errReader
                val out = Gathered()
                var read: Throwable? = null
                val outReader = thread(name = "su-out") {
                    if (stdout == null) {
                        gather(process.inputStream, out)
                    } else {
                        try {
                            process.inputStream.use(stdout)
                        } catch (e: Exception) {
                            read = e
                        }
                    }
                }
                readers += outReader
                var fed: Throwable? = null
                try {
                    process.outputStream.use { input -> stdin?.invoke(input) }
                } catch (e: IOException) {
                    // The command may have ended before taking all of its input; its own
                    // output says what happened.
                    fed = e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    fed = e
                }
                val exit = process.waitFor()
                outReader.join()
                errReader.join()
                read?.let { throw IOException("The output could not be taken whole", it) }
                fed?.let { if (exit == 0) throw IOException("The input could not be given whole", it) }
                Outcome.Ran(exit, out.text.toString(), err.text.toString(), cut = out.cut)
            }
        } finally {
            killer.cancel()
        }
    }

    /** The text of a stream as far as it is kept, and whether there was more. */
    private class Gathered {
        val text = StringBuilder()
        @Volatile var cut = false
    }

    private fun gather(stream: InputStream, into: Gathered) {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    val room = MAX_TEXT - into.text.length
                    if (n <= room) {
                        into.text.appendRange(buffer, 0, n)
                    } else {
                        if (room > 0) into.text.appendRange(buffer, 0, room)
                        into.cut = true
                    }
                }
            }
        } catch (_: IOException) {
        }
    }

    /** Whether the output of `id` is root's: a user id of 0, whatever the groups. */
    internal fun isRoot(idOutput: String): Boolean = idOutput.trimStart().startsWith("uid=0(")

    /**
     * The root app and its version as `su -v` tells them, "27.0:MAGISKSU" being Magisk 27.0;
     * anything else is given as it is, cut to a length that fits a line of the settings.
     */
    internal fun providerName(version: String): String {
        val text = version.trim().take(MAX_PROVIDER)
        val colon = text.indexOf(':')
        if (colon < 0) return text
        val number = text.substring(0, colon)
        val name = when (text.substring(colon + 1).uppercase()) {
            "MAGISKSU" -> "Magisk"
            "KERNELSU" -> "KernelSU"
            "APATCH" -> "APatch"
            else -> return text
        }
        return "$name $number"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
