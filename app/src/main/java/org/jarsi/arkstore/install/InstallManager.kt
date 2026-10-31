package org.jarsi.arkstore.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import org.jarsi.arkstore.R
import org.jarsi.arkstore.data.ApkInfo
import org.jarsi.arkstore.data.CatalogRepository
import org.jarsi.arkstore.data.CatalogRules
import org.jarsi.arkstore.data.Http
import org.jarsi.arkstore.data.InstalledApps
import org.jarsi.arkstore.data.StoreApp

sealed interface InstallState {
    /** Waiting for a turn to download, or for the other installs to finish first. */
    data object Queued : InstallState
    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(val progress: Float?) : InstallState
    data object Installing : InstallState
    data class Failed(val reason: FailReason, val detail: String? = null) : InstallState
}

enum class FailReason {
    DOWNLOAD, INVALID_APK, SIGNATURE_MISMATCH, INSTALL,
    /** The system gave the install up before the user could answer, and not for the first time; see [abortedBySystem]. */
    ABORTED
}

/** How an install that was waited for came out. */
enum class Outcome { INSTALLED, CONFIRMATION_NEEDED, FAILED, CANCELLED }

/** Whether [state] is one of an install on its way, as opposed to none or a failed one. */
internal fun InstallState?.isOnItsWay(): Boolean =
    this is InstallState.Queued || this is InstallState.Downloading || this is InstallState.Installing

/**
 * [states] with [repo] reserved for a download, or null when an install of it is already
 * on its way. Applied in one update of the states, since the screen and the background
 * check may ask for the same install at the same moment.
 */
internal fun reserved(states: Map<String, InstallState>, repo: String): Map<String, InstallState>? {
    if (states[repo].isOnItsWay()) return null
    return states + (repo to InstallState.Queued)
}

/**
 * [apps] in the order to install them: as given, except that the store itself, the app
 * whose package is [self], comes last. Installing it ends the process and everything that
 * is still on its way with it.
 */
internal fun installOrder(apps: List<StoreApp>, self: String): List<StoreApp> =
    apps.filter { it.packageName != self } + apps.filter { it.packageName == self }

/** Whether no install other than [repo]'s is on its way in [states]. */
internal fun othersSettled(states: Map<String, InstallState>, repo: String): Boolean =
    states.none { (name, state) -> name != repo && state.isOnItsWay() }

/**
 * Whether a result the system [reported] for a session is for the attempt on its way now:
 * the one [committed], or one still [downloading], which has no session yet, so that a named
 * one is an earlier attempt's, arriving late. A result that names no session, or one that
 * comes when nothing is on its way, as after the process was restarted, is taken as it is.
 */
internal fun belongsToCurrent(committed: Int?, reported: Int?, downloading: Boolean): Boolean = when {
    reported == null -> true
    committed != null -> committed == reported
    else -> !downloading
}

/**
 * Whether an attempt of a repository is on its way with no session yet: nothing [committed]
 * for it, while its [state] is one of an install on its way, from the reservation, which
 * comes before anything else of the attempt, to the hand-over. A result the system names
 * meanwhile is an earlier attempt's.
 */
internal fun attemptWithoutSession(committed: Int?, state: InstallState?): Boolean =
    committed == null && state.isOnItsWay()

/**
 * [states] with [repo]'s install settled as [state], none for one that went through, by a
 * result that passed its check a moment ago. A result of the attempt committed ([owned])
 * finds the state that attempt left installing; one with nothing committed, as after the
 * process was restarted, finds nothing on its way. Anything else is an attempt begun
 * since, which is left alone.
 */
internal fun afterResult(
    states: Map<String, InstallState>,
    repo: String,
    state: InstallState?,
    owned: Boolean
): Map<String, InstallState> {
    val current = states[repo]
    val settles = if (owned) current == InstallState.Installing else !current.isOnItsWay()
    return when {
        !settles -> states
        state == null -> states - repo
        else -> states + (repo to state)
    }
}

/** The outcome of one [attempt] of [repo], as it is settled, for whoever waits for that attempt. */
internal data class Settled(val repo: String, val attempt: Int, val outcome: Outcome) {
    fun isOf(repo: String, attempt: Int): Boolean = this.repo == repo && this.attempt == attempt
}

/** Whether [job] is the attempt numbered [attempt], or any attempt when none is named. */
internal fun isAttempt(job: InstallJob?, attempt: Int?): Boolean =
    job != null && (attempt == null || job.id == attempt)

/**
 * Whether an install the system reports as aborted was given up by the system itself rather
 * than refused by the user: no prompt was [presentedAt] all for the user to answer, or one
 * was presented less than [graceMillis] before [now], too short a time to read it and refuse.
 * Some devices give up the first session this way and ask only on the next, which the user
 * saw as a download that led nowhere until tried again.
 */
internal fun abortedBySystem(presentedAt: Long?, now: Long, graceMillis: Long): Boolean =
    presentedAt == null || now - presentedAt < graceMillis

/** The step of a download that has [read] bytes of [total], or of [fallback] when the response did not say. */
internal const val UNKNOWN_STEP = -1
/** The step before any was reported; no download reports it, so the first is always a change. */
internal const val NO_STEP_YET = -2
internal fun downloadStep(read: Long, total: Long, fallback: Long): Int {
    val size = if (total > 0) total else fallback
    return if (size > 0) (read * 100 / size).toInt() else UNKNOWN_STEP
}

/**
 * The installs among [states] that are stuck: those installing whose session, by its id in
 * [sessions], the system no longer has among those [alive], and so will never report on.
 * One not yet in [sessions] is still being committed and is left alone.
 */
internal fun stale(states: Map<String, InstallState>, sessions: Map<String, Int>, alive: Set<Int>): List<String> =
    states.filter { (name, state) ->
        state is InstallState.Installing && sessions[name]?.let { it !in alive } == true
    }.keys.toList()

/**
 * [states] without [repo]'s failure; [states] as they are when it has none, so that an
 * install begun since the failure is left alone. Applied in one update, like [reserved].
 */
internal fun dismissed(states: Map<String, InstallState>, repo: String): Map<String, InstallState> =
    if (states[repo] is InstallState.Failed) states - repo else states

/** What the key of an install from files on the device begins with; no name of the catalogue does. */
internal const val LOCAL_PREFIX = "backup:"

/**
 * The key under which files of the device's own, of [packageName], are installed: apart from
 * the names of the catalogue ("owner/repo", "fdroid:org.example" and the like), so that such
 * an install is never taken for a row of the list.
 */
internal fun localKey(packageName: String): String = LOCAL_PREFIX + packageName

internal fun isLocal(key: String): Boolean = key.startsWith(LOCAL_PREFIX)

/** Downloads release APKs, or takes files from the device, and hands them to the system package installer. */
object InstallManager {
    private const val TAG = "InstallManager"
    private const val MAX_DOWNLOADS = 3
    /**
     * How long after its prompt closed an install is given to settle before the prompt counts
     * as left without an answer. The system reports a refusal at once and a go-ahead within
     * seconds for most apps; a prompt that the user left open or put aside reports nothing.
     */
    private const val PROMPT_GRACE_MS = 20 * 1000L
    /** A prompt presented this shortly before the system gave the install up was never answered, see [abortedBySystem]. */
    private const val INSTANT_ABORT_MS = 1500L
    /** A file in the download folder untouched this long belongs to no attempt of any process that lives. */
    private const val LEFTOVER_FILE_AGE_MS = 60 * 60 * 1000L
    /** Where the confirmations the system waits for are written down, see [restorePending]. */
    private const val PREFS_PENDING = "pending_installs"
    /** A session made this recently may be one this process is still writing down. */
    private const val LEFTOVER_AGE_MS = 60 * 1000L
    private const val STALE_GRACE_MS = 3 * 1000L

    /** The session id of a root install that got no session: `pm` refused to make one. */
    private const val NO_SESSION = -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    /** In-flight or failed installs keyed by the repository's full name. */
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()

    /** The system's request to confirm one install: its session and the prompt to open. */
    internal data class Confirmation(val sessionId: Int, val prompt: Intent)

    internal val confirmations = InstallConfirmationQueue<Confirmation>()

    /**
     * The installs whose prompt was presented and left without an answer. The system still
     * holds their files and waits; the screen offers each as a button that asks again, see
     * [resumeConfirmation], so that nothing has to be downloaded anew.
     */
    val awaitingUser: StateFlow<Set<String>> get() = confirmations.unanswered

    /** Whether the confirmations written down by an earlier process have been picked up. */
    private val restored = AtomicBoolean(false)

    /** How many of the store's screens are showing; there can be one per window. */
    private val screens = AtomicInteger(0)

    /**
     * What was committed for each repository, kept until the system reports the outcome,
     * with the number of the [attempt] it was, see [InstallJob.id].
     */
    internal data class Committed(
        val app: StoreApp?,
        val packageName: String,
        val sessionId: Int,
        val attempt: Int,
        /** Made through `pm` as root: the system reports nothing of it, the command's own verdict settles it. */
        val root: Boolean = false,
        /** The files handed over, each under its name in the session, kept in [keep] until the session settles. */
        val files: List<Pair<String, File>>? = null,
        /** The file, or the directory of files, deleted once the session has settled. */
        val keep: File? = null,
        /** Handed over once more already, after the system gave up the first session before asking. */
        val retried: Boolean = false
    )

    private val committed = ConcurrentHashMap<String, Committed>()

    /**
     * Held while an attempt is settled by its result and while a confirmation of an earlier
     * process is restored, so that neither can slip under the other: a result taken halfway
     * through a restoration would leave the finished install as installing for good.
     */
    private val settling = Any()

    /**
     * The sessions whose result has come in this process. A confirmation written down for
     * one of them is settled already, however much it still looks like one to restore.
     */
    private val settledSessions: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** The names of the installs from files on the device, which the catalogue knows nothing of. */
    private val labels = ConcurrentHashMap<String, String>()

    /** The attempt on its way for each repository, for [cancel]. */
    private val jobs = ConcurrentHashMap<String, InstallJob>()

    /** Numbers the attempts, so that each downloads to a file of its own. */
    private val attempts = AtomicInteger(0)

    /** Only so many files are fetched at once; the rest of a long list wait their turn. */
    private val downloads = Semaphore(MAX_DOWNLOADS)

    private val _activeJobs = MutableStateFlow(0)
    /** How many downloads and installs are being worked on right now. */
    val activeJobs: StateFlow<Int> = _activeJobs.asStateFlow()

    private val _installedChanged = MutableStateFlow(0)
    /** Ticks whenever an install finishes, so observers re-read the installed versions. */
    val installedChanged: StateFlow<Int> = _installedChanged.asStateFlow()

    /** The outcome of each attempt as it is settled, for [installAndAwait]. */
    private val outcomes = MutableSharedFlow<Settled>(extraBufferCapacity = 64)

    fun isBusy(repo: String): Boolean = _states.value[repo].isOnItsWay()

    /** The name to show for the install of [key]: the catalogue's, or the one given with files of the device. */
    fun titleOf(context: Context, key: String): String =
        labels[key] ?: CatalogRepository.get(context).titleOf(key)

    /**
     * Downloads and installs [app], unless an install of it is already on its way; returns
     * whether this one was begun. From the [foreground], the download is kept going by a
     * service if the user leaves the app; from the background, where no service may start,
     * the caller keeps the process alive instead.
     */
    fun install(context: Context, app: StoreApp, foreground: Boolean = true): Boolean =
        begin(context, app, foreground) != null

    /**
     * [install], returning the attempt begun, or null when none was. [onBegun] is told its
     * number before it runs: what the caller is told is this attempt's, whatever becomes
     * of it, and not read afterwards from a map that may by then hold a later attempt.
     */
    private fun begin(
        context: Context,
        app: StoreApp,
        foreground: Boolean,
        onBegun: (attempt: Int) -> Unit = {}
    ): InstallJob? {
        // A file of this attempt's own: the cleanup of an earlier attempt of the same app,
        // cancelled a moment ago, must not take the file of this one.
        val target = File(
            File(context.applicationContext.cacheDir, "apk"),
            "${attempts.incrementAndGet()}-${CatalogRules.downloadName(app.fullName)}"
        )
        return begin(context, app.fullName, foreground, target, onBegun) { appContext, install ->
            downloadAndInstall(appContext, app, target, install)
        }
    }

    /**
     * Begins the attempt of [key] that [body] carries out, unless one is already on its way;
     * returns the attempt begun, or null when none was. [target] is the file, or the directory
     * of files, the attempt works on, deleted when the attempt settles. [onBegun] is told the
     * attempt's number before it runs: what the caller is told is this attempt's, whatever
     * becomes of it, and not read afterwards from a map that may by then hold a later attempt.
     */
    private fun begin(
        context: Context,
        key: String,
        foreground: Boolean,
        target: File,
        onBegun: (attempt: Int) -> Unit,
        body: suspend (appContext: Context, install: InstallJob) -> Unit
    ): InstallJob? {
        val appContext = context.applicationContext
        if (!reserve(key)) return null
        _activeJobs.update { it + 1 }
        if (foreground) InstallService.start(appContext)

        lateinit var install: InstallJob
        install = InstallJob(
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    body(appContext, install)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing here may take the process down or leave the app stuck as "installing".
                    Log.w(TAG, "Install failed for $key", e)
                    fail(install, key, InstallState.Failed(FailReason.INSTALL, e.message))
                }
            }
        )
        // The cleanup is tied to the job rather than written in it, so that it is done even
        // when a cancel comes before the job's first turn and the body never runs. The
        // reservation is given up last: only then may a new attempt begin.
        install.onSettled { cancelled ->
            // The files of an attempt committed are kept until its session settles, see
            // settle: should the system give the session up before asking, they are handed
            // over once more. Any other attempt is done with them.
            if (committed[key]?.let { it.attempt == install.id && it.keep != null } != true) {
                target.deleteRecursively()
            }
            jobs.remove(key, install)
            _activeJobs.update { it - 1 }
            if (cancelled) {
                // Only a download is cancelled, and only on purpose: nothing to show for it.
                setState(key, null)
                outcomes.tryEmit(Settled(key, install.id, Outcome.CANCELLED))
            }
        }
        onBegun(install.id)
        // Stored before it runs, so that a cancel cannot miss it.
        jobs[key] = install
        install.start()
        return install
    }

    /** Installs [apps] in the order of [installOrder]. */
    fun installAll(context: Context, apps: List<StoreApp>) {
        installOrder(apps, context.packageName).forEach { install(context, it) }
    }

    /**
     * Stops the download of [repo], when it is one: an install that is already with the
     * system goes on. The file fetched so far is dropped and nothing is shown for it. With
     * [attempt], only that attempt is stopped, not a later one of the same app.
     */
    fun cancel(repo: String, attempt: Int? = null) {
        val job = jobs[repo]
        if (isAttempt(job, attempt)) job?.cancel()
    }

    /**
     * Marks [repo] as being downloaded, unless it is already on its way, in one step: the
     * screen and the background check may ask for the same install at the same moment.
     */
    private fun reserve(repo: String): Boolean {
        var got = false
        _states.update { states ->
            val next = reserved(states, repo)
            got = next != null
            next ?: states
        }
        return got
    }

    /**
     * [install]s [app] from the background and waits for the outcome: installed, waiting for
     * the user to confirm (a notification asks them), or failed. Null when nothing was
     * settled within [timeoutMillis]; the install itself goes on, and its outcome is handled
     * as any other's. [onBegun] is told the number of the attempt this call began, before
     * the wait: a caller that wants to give it up may then cancel that attempt and no other,
     * as one already on its way, which this call does not begin, is somebody else's.
     */
    suspend fun installAndAwait(
        context: Context,
        app: StoreApp,
        timeoutMillis: Long,
        onBegun: (attempt: Int) -> Unit = {}
    ): Outcome? = await(app.fullName, timeoutMillis, onBegun) { begun ->
        begin(context, app, foreground = false, onBegun = begun)
    }

    /**
     * Installs [packageName] from [files] in [dir], the base file first, as the attempt of
     * [key] (see [localKey]), shown under [label], and waits for the outcome as
     * [installAndAwait] does. The directory and everything in it are taken over here: the
     * attempt begun deletes them when it settles, and when none begins, as when an attempt
     * of [key] is already on its way, they are deleted before this returns. The store itself
     * is not installed this way, so nothing waits for other installs here.
     */
    suspend fun installLocalAndAwait(
        context: Context,
        key: String,
        label: String,
        packageName: String,
        dir: File,
        files: List<File>,
        timeoutMillis: Long,
        onBegun: (attempt: Int) -> Unit = {}
    ): Outcome? {
        labels[key] = label
        var begun = false
        val outcome = await(key, timeoutMillis, { attempt ->
            begun = true
            onBegun(attempt)
        }) { onBegunHere ->
            begin(context, key, foreground = false, target = dir, onBegun = onBegunHere) { appContext, install ->
                installLocal(appContext, key, packageName, files, install)
            }
        }
        if (!begun) dir.deleteRecursively()
        return outcome
    }

    /**
     * Waits for the outcome of the attempt of [key] that [begin] begins: installed, waiting
     * for the user to confirm (a notification asks them), or failed. Null when nothing was
     * settled within [timeoutMillis]; the install itself goes on, and its outcome is handled
     * as any other's. [onBegun] is told the number of the attempt begun, before the wait: a
     * caller that wants to give it up may then cancel that attempt and no other, as one
     * already on its way, which this call does not begin, is somebody else's.
     */
    private suspend fun await(
        key: String,
        timeoutMillis: Long,
        onBegun: (attempt: Int) -> Unit,
        begin: (onBegun: (attempt: Int) -> Unit) -> InstallJob?
    ): Outcome? = coroutineScope {
        // One already on its way settles on its own: a waiting confirmation is the user's to
        // give, and anything else is not worth waiting for twice.
        if (isBusy(key)) {
            return@coroutineScope if (confirmations.holds(key)) Outcome.CONFIRMATION_NEEDED else null
        }
        // Listening before starting, so that an outcome settled at once is not missed; the
        // attempt's number, known before anything of it can be settled, tells its outcome
        // from a late one of an earlier attempt.
        val attempt = AtomicInteger(0)
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            outcomes.first { it.isOf(key, attempt.get()) }.outcome
        }
        val begun = begin {
            attempt.set(it)
            onBegun(it)
        }
        if (begun == null) {
            outcome.cancel()
            return@coroutineScope null
        }
        withTimeoutOrNull(timeoutMillis) { outcome.await() } ?: run {
            outcome.cancel()
            null
        }
    }

    /**
     * Checks [files] of the device, the base file first, and hands them to the system as
     * [packageName]: the base file must be of that package, and must not be signed with a
     * key the installed app has never had. No conflict is remembered: the files are not the
     * catalogue's, and the catalogue's rows are not told anything by them.
     */
    private suspend fun installLocal(
        appContext: Context,
        key: String,
        packageName: String,
        files: List<File>,
        install: InstallJob
    ) {
        setState(key, InstallState.Installing)
        val archive = files.firstOrNull()?.let { readArchive(appContext, it) }
        if (archive == null || archive.packageName != packageName) {
            fail(install, key, InstallState.Failed(FailReason.INVALID_APK))
            return
        }
        if (!signaturesMatch(appContext, archive)) {
            fail(install, key, InstallState.Failed(FailReason.SIGNATURE_MISMATCH))
            return
        }
        // From here on the attempt is the system's: a cancel is refused, and one that came
        // first refuses this.
        install.handOver()
        try {
            commit(appContext, key, null, packageName, files.map { it.name to it }, install.id, keep = files.first().parentFile)
        } catch (e: Exception) {
            Log.w(TAG, "Install session failed for $key", e)
            committed.remove(key)
            fail(install, key, InstallState.Failed(FailReason.INSTALL, e.message))
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun downloadAndInstall(appContext: Context, app: StoreApp, target: File, install: InstallJob) {
        val job = coroutineContext[Job]
        try {
            downloads.withPermit {
                setState(app.fullName, InstallState.Downloading(0f))
                var lastStep = NO_STEP_YET
                Http.download(app.apkUrl, target) { read, total ->
                    // The stream is not interrupted by a cancel; this is where one is noticed.
                    if (job?.isActive == false) throw CancellationException("cancelled")
                    val step = downloadStep(read, total, app.apkSize)
                    if (step != lastStep) {
                        lastStep = step
                        setState(
                            app.fullName,
                            InstallState.Downloading(if (step == UNKNOWN_STEP) null else step / 100f)
                        )
                    }
                }
            }
        } catch (e: IOException) {
            // A cancel does not interrupt the stream; a download that fails after one is a
            // cancelled download, not a failed one: its state is taken down, not shown.
            job?.ensureActive()
            Log.w(TAG, "Download failed for ${app.fullName}", e)
            fail(install, app.fullName, InstallState.Failed(FailReason.DOWNLOAD))
            return
        }
        // A cancel that came after the last of the file was read is noticed here.
        job?.ensureActive()

        // A catalogue says what the file must be. Its files come from mirrors too, so one that
        // is anything else is not installed.
        if (app.sha256 != null && !app.sha256.equals(sha256(target), ignoreCase = true)) {
            Log.w(TAG, "Downloaded file of ${app.fullName} does not match its checksum")
            fail(install, app.fullName, InstallState.Failed(FailReason.INVALID_APK))
            return
        }

        val archive = readArchive(appContext, target)
        if (archive == null ||
            (app.packageName != null && archive.packageName != app.packageName)
        ) {
            fail(install, app.fullName, InstallState.Failed(FailReason.INVALID_APK))
            return
        }
        val versionCode = PackageInfoCompat.getLongVersionCode(archive)
        // Needed only when the manifest could not be read remotely. Storing it waits for a
        // refresh that is running, which an install should not have to do otherwise.
        if (app.packageName == null) {
            try {
                CatalogRepository.get(appContext).rememberApkInfo(
                    app.fullName,
                    app.assetId,
                    ApkInfo(
                        archive.packageName,
                        versionCode,
                        archive.versionName,
                        // A preview named by its codename comes out as CUR_DEVELOPMENT, which
                        // is no level.
                        archive.applicationInfo?.minSdkVersion
                            ?.takeIf { it in 1 until Build.VERSION_CODES.CUR_DEVELOPMENT }
                    )
                )
            } catch (e: IOException) {
                // Only a convenience for the next refresh; the install itself does not need it.
                Log.w(TAG, "Could not store APK details for ${app.fullName}", e)
            }
        }
        if (!signaturesMatch(appContext, archive)) {
            InstalledApps.rememberConflict(appContext, app, archive.packageName)
            fail(install, app.fullName, InstallState.Failed(FailReason.SIGNATURE_MISMATCH))
            // The app now counts as installed from elsewhere rather than as an update.
            notifyInstalledChanged()
            return
        }
        InstalledApps.forgetConflict(appContext, app, archive.packageName)

        // Installing the store itself ends this process, so it waits for every other install
        // to be done with, however long that takes: the wait shows as queued, and can be
        // cancelled like any download.
        if (archive.packageName == appContext.packageName) {
            setState(app.fullName, InstallState.Queued)
            _states.first { othersSettled(it, app.fullName) }
        }
        // From here on the attempt is the system's: a cancel is refused, and one that came
        // first refuses this.
        install.handOver()
        setState(app.fullName, InstallState.Installing)
        if (app.prerelease) InstalledApps.rememberBeta(appContext, archive.packageName, versionCode)
        try {
            commit(appContext, app.fullName, app, archive.packageName, listOf("base.apk" to target), install.id, keep = target)
        } catch (e: Exception) {
            Log.w(TAG, "Install session failed for ${app.fullName}", e)
            committed.remove(app.fullName)
            fail(install, app.fullName, InstallState.Failed(FailReason.INSTALL, e.message))
        }
    }

    /**
     * Called by each of the store's screens as it comes into view. An install whose session
     * the system has dropped without a word is failed here, so that it can be tried again
     * rather than shown as installing for good.
     */
    internal fun onScreenStarted(context: Context) {
        screens.incrementAndGet()
        scope.launch {
            restorePending(context)
            failStale(context)
        }
    }

    /**
     * Picks up where a process that ended left off, once per process. The confirmations
     * written down by [rememberPending] whose sessions the system still holds are asked
     * again, under those sessions, so that the downloads need not be made anew, see
     * [restore]; one whose session is gone is forgotten. Sessions of the store that the
     * system holds and nothing here knows of are left over from such a process too, and are
     * let go, unless made so recently that they may be this process's own, still being
     * written down.
     */
    private fun restorePending(context: Context) {
        if (!restored.compareAndSet(false, true)) return
        val installer = context.packageManager.packageInstaller
        val now = System.currentTimeMillis()
        // Files of attempts of a process that ended with their sessions unsettled.
        File(context.cacheDir, "apk").listFiles()
            ?.filter { now - it.lastModified() >= LEFTOVER_FILE_AGE_MS }
            ?.forEach { it.deleteRecursively() }
        val sessions = try {
            installer.mySessions.associate {
                it.sessionId to when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> it.createdMillis
                    // Older Androids do not tell when a session was made. One not yet sealed
                    // may be this process's own, still being written, and counts as made just
                    // now; one sealed waits for the user or for the system, and is old.
                    it.isSealed -> 0L
                    else -> now
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list install sessions", e)
            return
        }
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        val known = restoreRecords(
            prefs.all,
            sessions.keys,
            prompt = { runCatching { Intent.parseUri(it.prompt, Intent.URI_INTENT_SCHEME) }.getOrNull() },
            forget = { forgetPending(context, it) }
        ) + committed.values.filter { !it.root }.map { it.sessionId }
        for (sessionId in leftoverSessions(sessions, known, now, LEFTOVER_AGE_MS)) {
            Log.i(TAG, "Letting go of install session $sessionId that nobody waits for")
            runCatching { installer.abandonSession(sessionId) }
        }
    }

    /**
     * Picks up the confirmation [records] (each app's stored record, see [rememberPending])
     * whose sessions are among those [alive] in the system, see [restore], with the prompt
     * [prompt] reads from each; the rest are dropped with [forget]: what is no record, what
     * names no prompt that can be read, and what names a session that is gone. A record of an
     * app whose attempt is on its way is left alone whatever it says: the attempt wrote it
     * after the sessions were listed, and it is the attempt's own. Returns the sessions the
     * records kept speak for.
     */
    internal fun restoreRecords(
        records: Map<String, Any?>,
        alive: Set<Int>,
        prompt: (PendingConfirmation) -> Intent?,
        forget: (String) -> Unit
    ): Set<Int> {
        val known = mutableSetOf<Int>()
        for ((repo, value) in records) {
            val pending = PendingConfirmation.decode(repo, value as? String)
            val intent = pending?.let(prompt)
            if (pending == null || intent == null || pending.sessionId !in alive) {
                synchronized(settling) {
                    if (!committed.containsKey(repo) && !_states.value[repo].isOnItsWay()) forget(repo)
                }
                continue
            }
            known += pending.sessionId
            if (restore(pending, intent)) Log.i(TAG, "Install of $repo still waits for the user; asking again")
        }
        return known
    }

    /**
     * Picks up [pending], a confirmation written down by a process that ended, whose session
     * the system still holds: the install is on its way again, as an attempt of this process
     * under that session, and the user is asked again with [prompt]. Nothing is picked up
     * when an attempt of the app is on its way already, or when the session's result has come
     * in this process, as it may have while the record was being read: that install is done
     * with. Returns whether the confirmation was picked up.
     */
    internal fun restore(pending: PendingConfirmation, prompt: Intent): Boolean = synchronized(settling) {
        val repo = pending.repo
        if (pending.sessionId in settledSessions || committed.containsKey(repo)) return false
        var got = false
        _states.update { states ->
            got = !states[repo].isOnItsWay()
            if (got) states + (repo to InstallState.Installing) else states
        }
        if (!got) return false
        pending.label?.let { labels[repo] = it }
        committed[repo] = Committed(null, pending.packageName, pending.sessionId, attempts.incrementAndGet())
        confirmations.enqueue(repo, Confirmation(pending.sessionId, prompt))
        true
    }

    /**
     * Writes down the confirmation of [repo]'s attempt, see [PendingConfirmation]. Under the
     * lock, so that a record is written only while its attempt is committed, and dropped (see
     * [settle] and [restoreRecords]) only while none is.
     */
    private fun rememberPending(context: Context, repo: String, sessionId: Int, prompt: Intent) = synchronized(settling) {
        val packageName = committed[repo]?.packageName ?: return
        val record = PendingConfirmation(
            repo, sessionId, packageName, labels[repo], prompt.toUri(Intent.URI_INTENT_SCHEME)
        )
        context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).edit()
            .putString(repo, record.encode()).apply()
    }

    private fun forgetPending(context: Context, repo: String) {
        context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).edit().remove(repo).apply()
    }

    /**
     * The sessions known here are taken down before the system is asked, so that one made
     * in between is not taken for gone; and one found gone is looked at again after a
     * moment, as a session that has just finished is gone too, its result on its way.
     */
    private suspend fun failStale(context: Context) {
        val installer = context.packageManager.packageInstaller
        fun alive(): Set<Int>? = try {
            installer.mySessions.map { it.sessionId }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list install sessions", e)
            null
        }
        // A session made as root is not among the store's own, and settles on its own.
        val sessions = committed.filterValues { !it.root }.mapValues { it.value.sessionId }
        val candidates = stale(_states.value, sessions, alive() ?: return)
        if (candidates.isEmpty()) return
        delay(STALE_GRACE_MS)
        val again = alive() ?: return
        for (repo in candidates) {
            val sessionId = sessions.getValue(repo)
            if (committed[repo]?.sessionId != sessionId || sessionId in again) continue
            Log.w(TAG, "Install session $sessionId of $repo is gone without a result")
            onSessionResult(context, repo, PackageInstaller.STATUS_FAILURE, null, sessionId)
        }
    }

    /**
     * The prompt of [repo]'s install has closed, in the [presentation] the confirmations
     * numbered so. Its outcome arrives from the system, in time; a prompt left without an
     * answer settles nothing, so after a while the confirmation is parked, for the screen to
     * offer again as a button, unless it has been presented anew by then.
     */
    internal fun onPromptClosed(repo: String, presentation: Int) {
        scope.launch {
            delay(PROMPT_GRACE_MS)
            confirmations.park(repo, presentation)
        }
    }

    /**
     * The prompt of [repo]'s install is being opened from its notification. The confirmation
     * becomes the one being presented, so that the screen does not open it too; while another
     * is being presented, it is parked instead, see [InstallConfirmationQueue.hold]. Nothing
     * tells when such a prompt closes, so it is treated like one closed now: parked after the
     * grace, unless its result has come by then.
     */
    internal fun promptOpened(repo: String) {
        confirmations.hold(repo)?.let { onPromptClosed(repo, it) }
    }

    /** The user asks for the parked prompt of [repo] again; the screen presents it when it can. */
    fun resumeConfirmation(repo: String) {
        confirmations.resume(repo)
    }

    /**
     * A screen of the store is in front, having opened the prompt of [prompted], if any. A
     * confirmation being presented that this screen did not open has no prompt in front of it
     * that the screen knows of: the prompt went down with a screen or a task that is gone,
     * and nothing would ever report it closed. It is treated like one closed now.
     */
    internal fun onScreenResumed(prompted: String?) {
        val open = confirmations.active() ?: return
        if (open.repo != prompted) onPromptClosed(open.repo, confirmations.presentation())
    }

    /**
     * Called by each of the store's screens as it goes out of view. When none is left to ask,
     * the confirmations still waiting are handed to notifications: they may have arrived
     * while a screen was showing but busy with another prompt.
     */
    internal fun onScreenStopped(context: Context) {
        if (screens.decrementAndGet() > 0) return
        (confirmations.waiting() + confirmations.parked()).forEach {
            InstallService.showReady(context, it.repo, it.value.sessionId, it.value.prompt)
        }
    }

    fun dismissFailure(repo: String) = _states.update { dismissed(it, repo) }

    /**
     * The system wants the user to confirm the install of [repo]. The store's screen asks
     * when it is showing. Otherwise the download finished in the background, where nothing
     * may open a prompt, so a notification takes the user to it.
     */
    internal fun onConfirmationRequired(
        context: Context,
        repo: String,
        sessionId: Int,
        intent: Intent
    ) {
        if (!isCurrent(repo, sessionId)) {
            Log.i(TAG, "Confirmation of an earlier attempt of $repo ignored")
            return
        }
        setState(repo, InstallState.Installing)
        confirmations.enqueue(repo, Confirmation(sessionId, intent))
        rememberPending(context, repo, sessionId, intent)
        if (screens.get() == 0) InstallService.showReady(context, repo, sessionId, intent)
        committed[repo]?.let { outcomes.tryEmit(Settled(repo, it.attempt, Outcome.CONFIRMATION_NEEDED)) }
    }

    /**
     * Whether the session [sessionId] the system speaks of is the attempt of [repo] on its
     * way now, as far as what is [committed] of it says; see [belongsToCurrent].
     */
    private fun isCurrent(repo: String, sessionId: Int?, committed: Committed? = this.committed[repo]): Boolean =
        belongsToCurrent(
            committed?.sessionId,
            sessionId,
            downloading = attemptWithoutSession(committed?.sessionId, _states.value[repo])
        )

    /**
     * Called by [InstallReceiver] once the system has decided on a session, [sessionId] when
     * it says which: the result of an earlier attempt of the same app is left alone.
     */
    internal fun onSessionResult(
        context: Context,
        repo: String,
        status: Int,
        message: String?,
        sessionId: Int? = null
    ) {
        val settlement = settle(repo, status, message, sessionId) {
            InstallService.cancelReady(context, repo)
            forgetPending(context, repo)
        } ?: return
        if (settlement.retry) {
            settlement.attempted?.let { scope.launch { recommit(context.applicationContext, repo, it) } }
            return
        }
        settlement.attempted?.keep?.deleteRecursively()
        val app = settlement.attempted?.app
        // The system can see a conflict that comparing the certificates here did not.
        if (settlement.state?.reason == FailReason.SIGNATURE_MISMATCH && app != null) {
            InstalledApps.rememberConflict(context, app, settlement.attempted.packageName)
        }
        _installedChanged.update { it + 1 }
    }

    /**
     * What [settle] took: the attempt that was committed, if any, and the failure it left, if
     * any; or, with [retry], an attempt whose session the system gave up before asking, which
     * is to be handed over once more and is not settled.
     */
    internal class Settlement(val attempted: Committed?, val state: InstallState.Failed?, val retry: Boolean = false)

    /**
     * Hands the files of [attempted], an attempt of [repo] whose session the system gave up
     * before the user could answer, over once more, under the same attempt: its state stays
     * installing throughout, and whoever waits for it goes on waiting. A second such session
     * is settled as a failure the user can read about, see [FailReason.ABORTED].
     */
    private suspend fun recommit(context: Context, repo: String, attempted: Committed) {
        Log.i(TAG, "The system gave up the install of $repo before asking; handing the files over once more")
        try {
            commit(
                context, repo, attempted.app, attempted.packageName, checkNotNull(attempted.files), attempted.attempt,
                keep = attempted.keep, retried = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "Install session failed for $repo", e)
            committed.remove(repo)
            attempted.keep?.deleteRecursively()
            setState(repo, InstallState.Failed(FailReason.INSTALL, e.message))
            outcomes.tryEmit(Settled(repo, attempted.attempt, Outcome.FAILED))
        }
    }

    /**
     * Settles the attempt of [repo] by the result the system gave for the session [sessionId]
     * with [status] and [message]: what was committed of it is given up, its confirmation let
     * go, [cleanup] run for what else it left behind, and its state settled, for the screen
     * and for whoever waits for the attempt. The cleanup comes before the state, under the
     * lock: a new attempt begun the moment the app is free leaves a record and a notification
     * of its own, which the old attempt's cleanup must not take. The result of an earlier
     * attempt, or of one settled already, is left alone: then null, and no cleanup.
     *
     * An install the system reports as aborted was refused by the user, which is no failure
     * worth showing, unless nobody could have answered yet, see [abortedBySystem]: then the
     * files are handed over once more when they are still here, and the state is not
     * settled; a second time, or without the files, the failure is shown and explained.
     */
    internal fun settle(repo: String, status: Int, message: String?, sessionId: Int?, cleanup: () -> Unit): Settlement? =
        synchronized(settling) {
            val attempted = committed[repo]
            if (!isCurrent(repo, sessionId, attempted)) {
                Log.i(TAG, "Result of an earlier attempt of $repo ignored")
                return null
            }
            // The attempt is taken in one step: the system and the check for stale sessions
            // can report on the same session at the same moment, and only one of them may
            // settle it.
            if (attempted != null && !committed.remove(repo, attempted)) {
                Log.i(TAG, "Result of $repo settled already")
                return null
            }
            sessionId?.let { settledSessions += it }
            val bySystem = status == PackageInstaller.STATUS_FAILURE_ABORTED &&
                abortedBySystem(confirmations.presentedAt(repo), System.currentTimeMillis(), INSTANT_ABORT_MS)
            confirmations.remove(repo)
            cleanup()
            if (bySystem && attempted?.files != null && !attempted.retried) {
                return Settlement(attempted, null, retry = true)
            }
            val (state, outcome) = when (status) {
                PackageInstaller.STATUS_SUCCESS -> null to Outcome.INSTALLED
                // The user backed out of the system prompt; that is not an error worth showing.
                PackageInstaller.STATUS_FAILURE_ABORTED ->
                    (if (bySystem) InstallState.Failed(FailReason.ABORTED) else null) to Outcome.FAILED
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    InstallState.Failed(conflictReason(message), message) to Outcome.FAILED
                else -> InstallState.Failed(FailReason.INSTALL, message) to Outcome.FAILED
            }
            // Only the state of the attempt taken is settled: an attempt begun since the
            // check, which a result that was not taken may have let through, is left as it is.
            _states.update { afterResult(it, repo, state, owned = attempted != null) }
            attempted?.let { outcomes.tryEmit(Settled(repo, it.attempt, outcome)) }
            Settlement(attempted, state)
        }

    fun notifyInstalledChanged() = _installedChanged.update { it + 1 }

    private fun conflictReason(message: String?): FailReason =
        if (message?.contains("signature", ignoreCase = true) == true ||
            message?.contains("UPDATE_INCOMPATIBLE") == true
        ) {
            FailReason.SIGNATURE_MISMATCH
        } else {
            FailReason.INSTALL
        }

    private fun setState(repo: String, state: InstallState?) {
        _states.update { if (state == null) it - repo else it + (repo to state) }
    }

    /**
     * Fails the attempt [install] of [repo] with [state], for the screen and for whoever
     * waits for it. The attempt is over first, so that a cancel landing now is refused and
     * the cleanup of a cancelled attempt cannot take down the state of the next one, begun
     * once the failure showed; one cancelled before is left as cancelled.
     */
    private fun fail(install: InstallJob, repo: String, state: InstallState.Failed) {
        if (!install.end()) return
        setState(repo, state)
        outcomes.tryEmit(Settled(repo, install.id, Outcome.FAILED))
    }

    /**
     * Hands [files] to the system in one session, each under the name given with it: the base
     * file as "base.apk", and the splits of a bundle under their own names, so that an app
     * installed in parts is restored in parts. With root turned on, the session is made
     * through `pm` as root instead, which asks the user nothing; when root cannot be had just
     * now, the ordinary session is made after all. [keep] is the file or directory the files
     * are in, kept until the session settles, see [settle]; [retried] says this is the second
     * session of the attempt.
     */
    private suspend fun commit(
        context: Context,
        key: String,
        app: StoreApp?,
        packageName: String,
        files: List<Pair<String, File>>,
        attempt: Int,
        keep: File? = null,
        retried: Boolean = false
    ) {
        if (Root.enabled(context) && commitAsRoot(context, key, app, packageName, files, attempt)) return
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        params.setSize(files.sumOf { it.second.length() })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }

        val sessionId = installer.createSession(params)
        committed[key] = Committed(app, packageName, sessionId, attempt, files = files, keep = keep, retried = retried)
        try {
            write(context, installer, sessionId, key, files)
        } catch (e: Exception) {
            // Closing a session keeps what was copied into it; only abandoning frees the space.
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    /**
     * [commit] as root; false when root could not be had, in which case nothing was
     * committed and the ordinary way is open. The verdict of `pm` is taken as the system's
     * would be, so that whoever waits for the install is told the same way.
     */
    private suspend fun commitAsRoot(
        context: Context,
        key: String,
        app: StoreApp?,
        packageName: String,
        files: List<Pair<String, File>>,
        attempt: Int
    ): Boolean {
        if (Root.check() !is RootCheck.Granted) {
            Log.w(TAG, "Root was not given; $key is installed the ordinary way")
            Root.markLost(context, true)
            return false
        }
        Root.markLost(context, false)
        var sessionId: Int? = null
        val result = RootInstaller.install(context.packageName, packageName, files) { id ->
            sessionId = id
            committed[key] = Committed(app, packageName, id, attempt, root = true)
        }
        return when (result) {
            RootInstaller.Result.NoRoot -> {
                Log.w(TAG, "Root was lost during the install of $key, which goes the ordinary way")
                sessionId?.let { committed.remove(key, Committed(app, packageName, it, attempt, root = true)) }
                Root.markLost(context, true)
                false
            }
            is RootInstaller.Result.Done -> {
                if (sessionId == null) {
                    // No session was made: the verdict is the install's all the same.
                    committed[key] = Committed(app, packageName, NO_SESSION, attempt, root = true)
                }
                onSessionResult(context, key, result.status, result.message, sessionId ?: NO_SESSION)
                true
            }
        }
    }

    /**
     * Removes [packageName] as root, without a word to the user; [onNoRoot] is called, on the
     * main thread, when root cannot be had, for the caller to ask the system instead. A
     * failure of the removal is told as a toast, as the system's dialog would tell it.
     */
    fun uninstall(context: Context, packageName: String, onNoRoot: () -> Unit) {
        val appContext = context.applicationContext
        scope.launch {
            val result = if (Root.check() is RootCheck.Granted) RootInstaller.uninstall(packageName) else RootInstaller.Result.NoRoot
            when (result) {
                RootInstaller.Result.NoRoot -> {
                    Root.markLost(appContext, true)
                    withContext(Dispatchers.Main) { onNoRoot() }
                }
                is RootInstaller.Result.Done -> {
                    Root.markLost(appContext, false)
                    if (result.status == PackageInstaller.STATUS_SUCCESS) {
                        notifyInstalledChanged()
                    } else {
                        Log.w(TAG, "Could not uninstall $packageName as root: ${result.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                appContext,
                                appContext.getString(R.string.uninstall_failed, result.message ?: ""),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun write(
        context: Context,
        installer: PackageInstaller,
        sessionId: Int,
        repo: String,
        files: List<Pair<String, File>>
    ) {
        installer.openSession(sessionId).use { session ->
            for ((name, file) in files) {
                session.openWrite(name, 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output, 64 * 1024) }
                    session.fsync(output)
                }
            }
            val intent = Intent(context, InstallReceiver::class.java)
                .setAction(InstallReceiver.ACTION_STATUS)
                .putExtra(InstallReceiver.EXTRA_REPO, repo)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
        }
    }

    @Suppress("DEPRECATION")
    private fun readArchive(context: Context, apk: File): PackageInfo? = try {
        // GET_SIGNATURES still works on archives for every supported Android version, whereas
        // GET_SIGNING_CERTIFICATES returns nothing for them before Android 13.
        context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
    } catch (e: Exception) {
        Log.w(TAG, "Could not parse ${apk.name}", e)
        null
    }

    /**
     * False only when the app is installed and provably signed with a different key: one the
     * installed app has never had, see [CatalogRules.mayUpdate].
     */
    @Suppress("DEPRECATION")
    private fun signaturesMatch(context: Context, archive: PackageInfo): Boolean {
        val archiveSigners = archive.signatures?.map { InstalledApps.digest(it.toByteArray()) }.orEmpty()
        return CatalogRules.mayUpdate(
            archiveSigners,
            InstalledApps.keysHeld(context.packageManager, archive.packageName)
        )
    }
}
