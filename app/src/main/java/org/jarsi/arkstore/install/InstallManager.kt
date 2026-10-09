package org.jarsi.arkstore.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
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

enum class FailReason { DOWNLOAD, INVALID_APK, SIGNATURE_MISMATCH, INSTALL }

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

/** Downloads release APKs and hands them to the system package installer. */
object InstallManager {
    private const val TAG = "InstallManager"
    private const val MAX_DOWNLOADS = 3
    private const val PROMPT_GRACE_MS = 3 * 60 * 1000L
    private const val STALE_GRACE_MS = 3 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    /** In-flight or failed installs keyed by the repository's full name. */
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()

    /** The system's request to confirm one install: its session and the prompt to open. */
    internal data class Confirmation(val sessionId: Int, val prompt: Intent)

    internal val confirmations = InstallConfirmationQueue<Confirmation>()

    /** How many of the store's screens are showing; there can be one per window. */
    private val screens = AtomicInteger(0)

    /**
     * What was committed for each repository, kept until the system reports the outcome,
     * with the number of the [attempt] it was, see [InstallJob.id].
     */
    private data class Committed(val app: StoreApp, val packageName: String, val sessionId: Int, val attempt: Int)

    private val committed = ConcurrentHashMap<String, Committed>()

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
        val appContext = context.applicationContext
        if (!reserve(app.fullName)) return null
        _activeJobs.update { it + 1 }
        if (foreground) InstallService.start(appContext)

        // A file of this attempt's own: the cleanup of an earlier attempt of the same app,
        // cancelled a moment ago, must not take the file of this one.
        val target = File(
            File(appContext.cacheDir, "apk"),
            "${attempts.incrementAndGet()}-${CatalogRules.downloadName(app.fullName)}"
        )
        lateinit var install: InstallJob
        install = InstallJob(
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    downloadAndInstall(appContext, app, target, install)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing here may take the process down or leave the app stuck as "installing".
                    Log.w(TAG, "Install failed for ${app.fullName}", e)
                    fail(app.fullName, install.id, InstallState.Failed(FailReason.INSTALL, e.message))
                }
            }
        )
        // The cleanup is tied to the job rather than written in it, so that it is done even
        // when a cancel comes before the job's first turn and the body never runs. The
        // reservation is given up last: only then may a new attempt begin.
        install.onSettled { cancelled ->
            // Once committed, the session holds its own copy of the file.
            target.delete()
            jobs.remove(app.fullName, install)
            _activeJobs.update { it - 1 }
            if (cancelled) {
                // Only a download is cancelled, and only on purpose: nothing to show for it.
                setState(app.fullName, null)
                outcomes.tryEmit(Settled(app.fullName, install.id, Outcome.CANCELLED))
            }
        }
        onBegun(install.id)
        // Stored before it runs, so that a cancel cannot miss it.
        jobs[app.fullName] = install
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
    ): Outcome? = coroutineScope {
        // One already on its way settles on its own: a waiting confirmation is the user's to
        // give, and anything else is not worth waiting for twice.
        if (isBusy(app.fullName)) {
            val asked = confirmations.waiting().any { it.repo == app.fullName } ||
                confirmations.next.value?.repo == app.fullName
            return@coroutineScope if (asked) Outcome.CONFIRMATION_NEEDED else null
        }
        // Listening before starting, so that an outcome settled at once is not missed; the
        // attempt's number, known before anything of it can be settled, tells its outcome
        // from a late one of an earlier attempt.
        val attempt = AtomicInteger(0)
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            outcomes.first { it.isOf(app.fullName, attempt.get()) }.outcome
        }
        val begun = begin(context, app, foreground = false) {
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
            Log.w(TAG, "Download failed for ${app.fullName}", e)
            fail(app.fullName, install.id, InstallState.Failed(FailReason.DOWNLOAD))
            return
        }
        // A cancel that came after the last of the file was read is noticed here.
        job?.ensureActive()

        // A catalogue says what the file must be. Its files come from mirrors too, so one that
        // is anything else is not installed.
        if (app.sha256 != null && !app.sha256.equals(sha256(target), ignoreCase = true)) {
            Log.w(TAG, "Downloaded file of ${app.fullName} does not match its checksum")
            fail(app.fullName, install.id, InstallState.Failed(FailReason.INVALID_APK))
            return
        }

        val archive = readArchive(appContext, target)
        if (archive == null ||
            (app.packageName != null && archive.packageName != app.packageName)
        ) {
            fail(app.fullName, install.id, InstallState.Failed(FailReason.INVALID_APK))
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
            fail(app.fullName, install.id, InstallState.Failed(FailReason.SIGNATURE_MISMATCH))
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
            commit(appContext, app, archive.packageName, target, install.id)
        } catch (e: Exception) {
            Log.w(TAG, "Install session failed for ${app.fullName}", e)
            committed.remove(app.fullName)
            fail(app.fullName, install.id, InstallState.Failed(FailReason.INSTALL, e.message))
        }
    }

    /**
     * Called by each of the store's screens as it comes into view. An install whose session
     * the system has dropped without a word is failed here, so that it can be tried again
     * rather than shown as installing for good.
     */
    internal fun onScreenStarted(context: Context) {
        screens.incrementAndGet()
        scope.launch { failStale(context) }
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
        val sessions = committed.mapValues { it.value.sessionId }
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
     * The prompt of [repo]'s install, of the session [sessionId], has closed. Its outcome
     * arrives from the system, in time; a prompt left without an answer settles nothing, so
     * after a while the confirmation is put behind the others, to be asked again later.
     */
    internal fun onPromptClosed(repo: String, sessionId: Int) {
        scope.launch {
            delay(PROMPT_GRACE_MS)
            confirmations.abandon(repo) { it.sessionId == sessionId }
        }
    }

    /**
     * Called by each of the store's screens as it goes out of view. When none is left to ask,
     * the confirmations still waiting are handed to notifications: they may have arrived
     * while a screen was showing but busy with another prompt.
     */
    internal fun onScreenStopped(context: Context) {
        if (screens.decrementAndGet() > 0) return
        confirmations.waiting().forEach {
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
        val attempted = committed[repo]
        if (!isCurrent(repo, sessionId, attempted)) {
            Log.i(TAG, "Result of an earlier attempt of $repo ignored")
            return
        }
        // The attempt is taken in one step: the system and the check for stale sessions can
        // report on the same session at the same moment, and only one of them may settle it.
        if (attempted != null && !committed.remove(repo, attempted)) {
            Log.i(TAG, "Result of $repo settled already")
            return
        }
        InstallService.cancelReady(context, repo)
        confirmations.remove(repo)
        val (state, outcome) = when (status) {
            PackageInstaller.STATUS_SUCCESS -> null to Outcome.INSTALLED
            // The user backed out of the system prompt; that is not an error worth showing.
            PackageInstaller.STATUS_FAILURE_ABORTED -> null to Outcome.FAILED
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                val reason = conflictReason(message)
                // The system can see a conflict that comparing the certificates here did not.
                if (reason == FailReason.SIGNATURE_MISMATCH && attempted != null) {
                    InstalledApps.rememberConflict(context, attempted.app, attempted.packageName)
                }
                InstallState.Failed(reason, message) to Outcome.FAILED
            }
            else -> InstallState.Failed(FailReason.INSTALL, message) to Outcome.FAILED
        }
        // Only the state of the attempt taken is settled: an attempt begun since the check,
        // which a result that was not taken may have let through, is left as it is.
        _states.update { afterResult(it, repo, state, owned = attempted != null) }
        attempted?.let { outcomes.tryEmit(Settled(repo, it.attempt, outcome)) }
        _installedChanged.update { it + 1 }
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

    /** Fails the attempt numbered [attempt] of [repo] with [state], for the screen and for whoever waits for it. */
    private fun fail(repo: String, attempt: Int, state: InstallState.Failed) {
        setState(repo, state)
        outcomes.tryEmit(Settled(repo, attempt, Outcome.FAILED))
    }

    private fun commit(context: Context, app: StoreApp, packageName: String, apk: File, attempt: Int) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }

        val sessionId = installer.createSession(params)
        committed[app.fullName] = Committed(app, packageName, sessionId, attempt)
        try {
            write(context, installer, sessionId, app.fullName, apk)
        } catch (e: Exception) {
            // Closing a session keeps what was copied into it; only abandoning frees the space.
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private fun write(
        context: Context,
        installer: PackageInstaller,
        sessionId: Int,
        repo: String,
        apk: File
    ) {
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output, 64 * 1024) }
                session.fsync(output)
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
