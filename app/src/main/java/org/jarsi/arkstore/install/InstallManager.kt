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
    private const val SELF_WAIT_MS = 10 * 60 * 1000L
    private const val PROMPT_GRACE_MS = 3 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    /** In-flight or failed installs keyed by the repository's full name. */
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()

    /** The system's request to confirm one install: its session and the prompt to open. */
    internal data class Confirmation(val sessionId: Int, val prompt: Intent)

    internal val confirmations = InstallConfirmationQueue<Confirmation>()

    /** How many of the store's screens are showing; there can be one per window. */
    private val screens = AtomicInteger(0)

    /** What was committed for each repository, kept until the system reports the outcome. */
    private data class Committed(val app: StoreApp, val packageName: String, val sessionId: Int)

    private val committed = ConcurrentHashMap<String, Committed>()

    /** The job of each install on its way, for [cancel]. */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Only so many files are fetched at once; the rest of a long list wait their turn. */
    private val downloads = Semaphore(MAX_DOWNLOADS)

    private val _activeJobs = MutableStateFlow(0)
    /** How many downloads and installs are being worked on right now. */
    val activeJobs: StateFlow<Int> = _activeJobs.asStateFlow()

    private val _installedChanged = MutableStateFlow(0)
    /** Ticks whenever an install finishes, so observers re-read the installed versions. */
    val installedChanged: StateFlow<Int> = _installedChanged.asStateFlow()

    /** The outcome of each install as it is settled, for [installAndAwait]. */
    private val outcomes = MutableSharedFlow<Pair<String, Outcome>>(extraBufferCapacity = 64)

    fun isBusy(repo: String): Boolean = _states.value[repo].isOnItsWay()

    /**
     * Downloads and installs [app], unless an install of it is already on its way; returns
     * whether this one was begun. From the [foreground], the download is kept going by a
     * service if the user leaves the app; from the background, where no service may start,
     * the caller keeps the process alive instead.
     */
    fun install(context: Context, app: StoreApp, foreground: Boolean = true): Boolean {
        val appContext = context.applicationContext
        if (!reserve(app.fullName)) return false
        _activeJobs.update { it + 1 }
        if (foreground) InstallService.start(appContext)

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val target = File(File(appContext.cacheDir, "apk"), CatalogRules.downloadName(app.fullName))
            try {
                downloadAndInstall(appContext, app, target)
            } catch (e: CancellationException) {
                // Only a download is cancelled, and only on purpose: nothing to show for it.
                setState(app.fullName, null)
                outcomes.tryEmit(app.fullName to Outcome.CANCELLED)
                throw e
            } catch (e: Exception) {
                // Nothing here may take the process down or leave the app stuck as "installing".
                Log.w(TAG, "Install failed for ${app.fullName}", e)
                setState(app.fullName, InstallState.Failed(FailReason.INSTALL, e.message))
            } finally {
                // Once committed, the session holds its own copy of the file.
                target.delete()
                jobs.remove(app.fullName)
                _activeJobs.update { it - 1 }
            }
        }
        // Stored before it runs, so that a cancel cannot miss it.
        jobs[app.fullName] = job
        job.start()
        return true
    }

    /** Installs [apps] in the order of [installOrder]. */
    fun installAll(context: Context, apps: List<StoreApp>) {
        installOrder(apps, context.packageName).forEach { install(context, it) }
    }

    /**
     * Stops the download of [repo], when it is one: an install that is already with the
     * system goes on. The file fetched so far is dropped and nothing is shown for it.
     */
    fun cancel(repo: String) {
        val state = _states.value[repo]
        if (state !is InstallState.Queued && state !is InstallState.Downloading) return
        jobs[repo]?.cancel(CancellationException("cancelled"))
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
     * as any other's.
     */
    suspend fun installAndAwait(context: Context, app: StoreApp, timeoutMillis: Long): Outcome? = coroutineScope {
        // One already on its way settles on its own: a waiting confirmation is the user's to
        // give, and anything else is not worth waiting for twice.
        if (isBusy(app.fullName)) {
            val asked = confirmations.waiting().any { it.repo == app.fullName } ||
                confirmations.next.value?.repo == app.fullName
            return@coroutineScope if (asked) Outcome.CONFIRMATION_NEEDED else null
        }
        // Listening before starting, so that an outcome settled at once is not missed.
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            outcomes.first { it.first == app.fullName }.second
        }
        install(context, app, foreground = false)
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

    private suspend fun downloadAndInstall(appContext: Context, app: StoreApp, target: File) {
        val job = coroutineContext[Job]
        try {
            downloads.withPermit {
                setState(app.fullName, InstallState.Downloading(0f))
                var lastStep = -1
                Http.download(app.apkUrl, target) { read, total ->
                    // The stream is not interrupted by a cancel; this is where one is noticed.
                    if (job?.isActive == false) throw CancellationException("cancelled")
                    val size = if (total > 0) total else app.apkSize
                    val step = if (size > 0) (read * 100 / size).toInt() else -1
                    if (step != lastStep) {
                        lastStep = step
                        setState(
                            app.fullName,
                            InstallState.Downloading(if (step < 0) null else step / 100f)
                        )
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Download failed for ${app.fullName}", e)
            setState(app.fullName, InstallState.Failed(FailReason.DOWNLOAD))
            return
        }

        // A catalogue says what the file must be. Its files come from mirrors too, so one that
        // is anything else is not installed.
        if (app.sha256 != null && !app.sha256.equals(sha256(target), ignoreCase = true)) {
            Log.w(TAG, "Downloaded file of ${app.fullName} does not match its checksum")
            setState(app.fullName, InstallState.Failed(FailReason.INVALID_APK))
            return
        }

        val archive = readArchive(appContext, target)
        if (archive == null ||
            (app.packageName != null && archive.packageName != app.packageName)
        ) {
            setState(app.fullName, InstallState.Failed(FailReason.INVALID_APK))
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
            setState(app.fullName, InstallState.Failed(FailReason.SIGNATURE_MISMATCH))
            // The app now counts as installed from elsewhere rather than as an update.
            notifyInstalledChanged()
            return
        }
        InstalledApps.forgetConflict(appContext, app, archive.packageName)

        // Installing the store itself ends this process, so it waits for every other
        // install to be done with; but not forever for one the user never confirms.
        if (archive.packageName == appContext.packageName) {
            setState(app.fullName, InstallState.Queued)
            withTimeoutOrNull(SELF_WAIT_MS) { _states.first { othersSettled(it, app.fullName) } }
        }
        setState(app.fullName, InstallState.Installing)
        if (app.prerelease) InstalledApps.rememberBeta(appContext, archive.packageName, versionCode)
        try {
            commit(appContext, app, archive.packageName, target)
        } catch (e: Exception) {
            Log.w(TAG, "Install session failed for ${app.fullName}", e)
            committed.remove(app.fullName)
            setState(app.fullName, InstallState.Failed(FailReason.INSTALL, e.message))
        }
    }

    /**
     * Called by each of the store's screens as it comes into view. An install whose session
     * the system has dropped without a word is failed here, so that it can be tried again
     * rather than shown as installing for good.
     */
    internal fun onScreenStarted(context: Context) {
        screens.incrementAndGet()
        val alive = try {
            context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list install sessions", e)
            return
        }
        val sessions = committed.mapValues { it.value.sessionId }
        for (repo in stale(_states.value, sessions, alive)) {
            Log.w(TAG, "Install session of $repo is gone without a result")
            onSessionResult(context, repo, PackageInstaller.STATUS_FAILURE, null)
        }
    }

    /**
     * The prompt of [repo]'s install has closed. Its outcome arrives from the system, in
     * time; a prompt left without an answer settles nothing, so after a while the next
     * confirmation is let through regardless.
     */
    internal fun onPromptClosed(repo: String) {
        scope.launch {
            delay(PROMPT_GRACE_MS)
            confirmations.abandon(repo)
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
        setState(repo, InstallState.Installing)
        confirmations.enqueue(repo, Confirmation(sessionId, intent))
        if (screens.get() == 0) InstallService.showReady(context, repo, sessionId, intent)
        outcomes.tryEmit(repo to Outcome.CONFIRMATION_NEEDED)
    }

    /** Called by [InstallReceiver] once the system has decided on a session. */
    internal fun onSessionResult(context: Context, repo: String, status: Int, message: String?) {
        confirmations.remove(repo)
        val attempted = committed.remove(repo)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                setState(repo, null)
                outcomes.tryEmit(repo to Outcome.INSTALLED)
            }
            // The user backed out of the system prompt; that is not an error worth showing.
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                setState(repo, null)
                outcomes.tryEmit(repo to Outcome.FAILED)
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                val reason = conflictReason(message)
                // The system can see a conflict that comparing the certificates here did not.
                if (reason == FailReason.SIGNATURE_MISMATCH && attempted != null) {
                    InstalledApps.rememberConflict(context, attempted.app, attempted.packageName)
                }
                setState(repo, InstallState.Failed(reason, message))
            }
            else -> setState(repo, InstallState.Failed(FailReason.INSTALL, message))
        }
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
        if (state is InstallState.Failed) outcomes.tryEmit(repo to Outcome.FAILED)
    }

    private fun commit(context: Context, app: StoreApp, packageName: String, apk: File) {
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
        committed[app.fullName] = Committed(app, packageName, sessionId)
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
