package org.jarsi.arkstore.install

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
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
    /** Checked, and being installed through root or saved for the user. */
    data object Installing : InstallState
    data class Failed(val reason: FailReason, val detail: String? = null) : InstallState
}

enum class FailReason {
    DOWNLOAD, INVALID_APK, SIGNATURE_MISMATCH, INSTALL,
    /** The checked file could not be saved to the folder for the user to install. */
    SAVE,
    /** Root, the only way the store installs anything itself, could not be had. */
    NO_ROOT
}

/** How an install that was waited for came out: installed, or the file [SAVED] for the user to install. */
enum class Outcome { INSTALLED, SAVED, FAILED, CANCELLED }

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

/**
 * Downloads release APKs, or takes files from the device, checks them, and installs them through
 * root when root is on and given. Without root the store cannot install anything itself: a file
 * the user asked for is saved to [ApkFolder] for them to install, and one the background check
 * asked for is not fetched for nothing.
 */
object InstallManager {
    private const val TAG = "InstallManager"
    private const val MAX_DOWNLOADS = 3
    /** Where the files saved for the user are written down, see [saved]. */
    private const val PREFS_SAVED = "saved_apks"
    private const val PREF_RECORDS = "records"
    /** Where the confirmations of the install prompt of earlier versions were written down. */
    private const val OLD_PREFS_PENDING = "pending_installs"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    /** In-flight or failed installs keyed by the repository's full name. */
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()

    private val _saved = MutableStateFlow<Map<String, SavedApk>>(emptyMap())
    /**
     * The files saved to the folder for the user to install, by package, whose version is not
     * installed yet and whose file is still there; see [loadSaved].
     */
    val saved: StateFlow<Map<String, SavedApk>> = _saved.asStateFlow()

    /** Guards [saved] and its record, which a save and a reload may change at the same moment. */
    private val savedLock = Any()

    private val _rootLost = MutableStateFlow(false)
    /**
     * Root is turned on, but the last install could not have it: the files are then saved for
     * the user, and the rows say so rather than offering installs that root would do.
     */
    val rootLost: StateFlow<Boolean> = _rootLost.asStateFlow()

    /** How many of the store's screens are showing; there can be one per window. */
    internal val screens = AtomicInteger(0)

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

    /** Stands in for [ApkFolder.save] in tests, returning where the file went; null outside them. */
    internal var saver: (suspend (Context, File, String) -> String)? = null

    /** Stands in for [ApkFolder.delete] in tests; null outside them. */
    internal var deleter: ((Context, String) -> Unit)? = null

    fun isBusy(repo: String): Boolean = _states.value[repo].isOnItsWay()

    /** The name to show for the install of [key]: the catalogue's, or the one given with files of the device. */
    fun titleOf(context: Context, key: String): String =
        labels[key] ?: CatalogRepository.get(context).titleOf(key)

    /**
     * Downloads and installs [app], or saves it for the user to install, unless an install of
     * it is already on its way; returns whether this one was begun. From the [foreground], the
     * download is kept going by a service if the user leaves the app; from the background,
     * where no service may start, the caller keeps the process alive instead.
     */
    fun install(context: Context, app: StoreApp, foreground: Boolean = true): Boolean =
        begin(context, app, foreground, saveWithoutRoot = true) != null

    /**
     * [install], returning the attempt begun, or null when none was. [onBegun] is told its
     * number before it runs: what the caller is told is this attempt's, whatever becomes
     * of it, and not read afterwards from a map that may by then hold a later attempt.
     * [saveWithoutRoot] says whether a file that root cannot install is saved for the user.
     */
    private fun begin(
        context: Context,
        app: StoreApp,
        foreground: Boolean,
        saveWithoutRoot: Boolean,
        onBegun: (attempt: Int) -> Unit = {}
    ): InstallJob? {
        // A file of this attempt's own: the cleanup of an earlier attempt of the same app,
        // cancelled a moment ago, must not take the file of this one.
        val target = File(
            File(context.applicationContext.cacheDir, "apk"),
            "${attempts.incrementAndGet()}-${CatalogRules.downloadName(app.fullName)}"
        )
        return begin(context, app.fullName, foreground, target, onBegun) { appContext, install ->
            downloadAndInstall(appContext, app, target, install, saveWithoutRoot)
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
            target.deleteRecursively()
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

    /** Installs, or downloads for the user, [apps] in the order of [installOrder]. */
    fun installAll(context: Context, apps: List<StoreApp>) {
        installOrder(apps, context.packageName).forEach { install(context, it) }
    }

    /**
     * Stops the download of [repo], when it is one: an install that root has begun goes on.
     * The file fetched so far is dropped and nothing is shown for it. With [attempt], only
     * that attempt is stopped, not a later one of the same app.
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
     * [install]s [app] from the background, through root alone, and waits for the outcome:
     * installed or failed. Without root nothing is saved: the background check only announces
     * what the user did not ask for. Null when nothing was settled within [timeoutMillis]; the
     * install itself goes on, and its outcome is handled as any other's. [onBegun] is told the
     * number of the attempt this call began, before the wait: a caller that wants to give it up
     * may then cancel that attempt and no other, as one already on its way, which this call
     * does not begin, is somebody else's.
     */
    suspend fun installAndAwait(
        context: Context,
        app: StoreApp,
        timeoutMillis: Long,
        onBegun: (attempt: Int) -> Unit = {}
    ): Outcome? = await(app.fullName, timeoutMillis, onBegun) { begun ->
        begin(context, app, foreground = false, saveWithoutRoot = false, onBegun = begun)
    }

    /**
     * Installs [packageName] from [files] in [dir] through root, the base file first, as the
     * attempt of [key] (see [localKey]), shown under [label], and waits for the outcome as
     * [installAndAwait] does. The directory and everything in it are taken over here: the
     * attempt begun deletes them when it settles, and when none begins, as when an attempt of
     * [key] is already on its way, they are deleted before this returns. The store itself is
     * not installed this way, so nothing waits for other installs here.
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
     * Waits for the outcome of the attempt of [key] that [begin] begins. Null when nothing was
     * settled within [timeoutMillis], or when an attempt of [key] was already on its way, which
     * settles on its own; the install itself goes on, and its outcome is handled as any
     * other's. [onBegun] is told the number of the attempt begun, before the wait.
     */
    private suspend fun await(
        key: String,
        timeoutMillis: Long,
        onBegun: (attempt: Int) -> Unit,
        begin: (onBegun: (attempt: Int) -> Unit) -> InstallJob?
    ): Outcome? = coroutineScope {
        if (isBusy(key)) return@coroutineScope null
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
     * Checks [files] of the device, the base file first, and installs them through root as
     * [packageName]: the base file must be of that package, and must not be signed with a key
     * the installed app has never had. No conflict is remembered: the files are not the
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
        val archive = files.firstOrNull()?.let { ApkCheck.readArchive(appContext, it) }
        if (archive == null || archive.packageName != packageName) {
            fail(install, key, InstallState.Failed(FailReason.INVALID_APK))
            return
        }
        if (!ApkCheck.signaturesMatch(appContext, archive)) {
            fail(install, key, InstallState.Failed(FailReason.SIGNATURE_MISMATCH))
            return
        }
        // From here on the attempt is root's: a cancel is refused, and one that came first
        // refuses this.
        install.handOver()
        val result = handOver(
            appContext, key, null, packageName, files.map { it.name to it },
            rootWanted = Root.enabled(appContext), saveAs = null
        )
        settle(appContext, install, key, null, packageName, result)
    }

    private suspend fun downloadAndInstall(
        appContext: Context,
        app: StoreApp,
        target: File,
        install: InstallJob,
        saveWithoutRoot: Boolean
    ) {
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

        val archive = ApkCheck.checkDownload(appContext, app, target)
        if (archive == null) {
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
        if (!ApkCheck.signaturesMatch(appContext, archive)) {
            InstalledApps.rememberConflict(appContext, app, archive.packageName)
            fail(install, app.fullName, InstallState.Failed(FailReason.SIGNATURE_MISMATCH))
            // The app now counts as installed from elsewhere rather than as an update.
            notifyInstalledChanged()
            return
        }
        InstalledApps.forgetConflict(appContext, app, archive.packageName)

        val rootWanted = Root.enabled(appContext)
        // Installing the store itself through root ends this process, so it waits for every
        // other install to be done with, however long that takes: the wait shows as queued,
        // and can be cancelled like any download. A file saved for the user ends nothing.
        if (rootWanted && archive.packageName == appContext.packageName) {
            setState(app.fullName, InstallState.Queued)
            _states.first { othersSettled(it, app.fullName) }
        }
        // From here on the attempt is root's or the folder's: a cancel is refused, and one
        // that came first refuses this.
        install.handOver()
        setState(app.fullName, InstallState.Installing)
        if (app.prerelease) InstalledApps.rememberBeta(appContext, archive.packageName, versionCode)
        val saveAs = if (saveWithoutRoot) {
            SaveAs(
                SavedApks.fileName(app.title, archive.versionName, versionCode, archive.packageName),
                versionCode,
                archive.versionName,
                app.fullName
            )
        } else {
            null
        }
        val result = handOver(appContext, app.fullName, app, archive.packageName, listOf("base.apk" to target), rootWanted, saveAs)
        settle(appContext, install, app.fullName, app, archive.packageName, result)
    }

    /** What a file saved for the user is saved as: its name, and what it is of. */
    internal data class SaveAs(val displayName: String, val versionCode: Long, val versionName: String?, val repo: String)

    /** What [handOver] came to. */
    internal sealed interface HandOver {
        data object Installed : HandOver
        data class Saved(val record: SavedApk) : HandOver
        data class Failed(val state: InstallState.Failed) : HandOver
    }

    /**
     * Installs the checked [files] of [packageName] through root when [rootWanted] and root is
     * given; otherwise saves the base file for the user to install, as [saveAs] says, or, with
     * no [saveAs], gives up without root. A file root refused is not saved instead: the system
     * has said why it would not take it.
     */
    internal suspend fun handOver(
        context: Context,
        key: String,
        app: StoreApp?,
        packageName: String,
        files: List<Pair<String, File>>,
        rootWanted: Boolean,
        saveAs: SaveAs?
    ): HandOver {
        if (rootWanted) commitAsRoot(context, key, packageName, files)?.let { return it }
        if (saveAs == null) return HandOver.Failed(InstallState.Failed(FailReason.NO_ROOT))
        val uri = try {
            val file = files.first().second
            saver?.invoke(context, file, saveAs.displayName) ?: ApkFolder.save(context, file, saveAs.displayName).toString()
        } catch (e: IOException) {
            Log.w(TAG, "Could not save the file of $key", e)
            return HandOver.Failed(InstallState.Failed(FailReason.SAVE, e.message))
        }
        val record = SavedApk(packageName, saveAs.versionCode, saveAs.versionName, uri, saveAs.repo)
        // From what is written down, not from what the rows show: a file whose version is
        // installed by now, or one from before this run, goes all the same.
        val update = synchronized(savedLock) {
            val current = readSaved(context)
            SavedApks.withSaved(current, record).also { if (it.records != current) writeSaved(context, it.records) }
        }
        for (uri in update.delete) deleter?.invoke(context, uri) ?: ApkFolder.delete(context, uri)
        return HandOver.Saved(update.kept)
    }

    /**
     * Installs [files] of [packageName] as root; null when root could not be had, in which
     * case nothing was installed and the folder is open.
     */
    private suspend fun commitAsRoot(
        context: Context,
        key: String,
        packageName: String,
        files: List<Pair<String, File>>
    ): HandOver? {
        if (Root.check() !is RootCheck.Granted) {
            Log.w(TAG, "Root was not given; $key is not installed through it")
            setRootLost(context, true)
            return null
        }
        setRootLost(context, false)
        return when (val result = RootInstaller.install(context.packageName, packageName, files) {}) {
            RootInstaller.Result.NoRoot -> {
                Log.w(TAG, "Root was lost during the install of $key")
                setRootLost(context, true)
                null
            }
            is RootInstaller.Result.Done -> when (result.status) {
                PackageInstaller.STATUS_SUCCESS -> HandOver.Installed
                PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    HandOver.Failed(InstallState.Failed(conflictReason(result.message), result.message))
                else -> HandOver.Failed(InstallState.Failed(FailReason.INSTALL, result.message))
            }
        }
    }

    /** Settles the attempt [install] of [key] by what [result] came to, for the screen and for whoever waits for it. */
    private fun settle(
        context: Context,
        install: InstallJob,
        key: String,
        app: StoreApp?,
        packageName: String,
        result: HandOver
    ) {
        when (result) {
            is HandOver.Failed -> {
                // The system can see a conflict that comparing the certificates here did not.
                if (result.state.reason == FailReason.SIGNATURE_MISMATCH && app != null) {
                    InstalledApps.rememberConflict(context, app, packageName)
                    notifyInstalledChanged()
                }
                fail(install, key, result.state)
            }
            HandOver.Installed -> {
                setState(key, null)
                outcomes.tryEmit(Settled(key, install.id, Outcome.INSTALLED))
                notifyInstalledChanged()
            }
            is HandOver.Saved -> {
                setState(key, null)
                outcomes.tryEmit(Settled(key, install.id, Outcome.SAVED))
                // With a screen showing, the row says so; otherwise a notification does.
                if (screens.get() == 0) InstallService.showSaved(context, titleOf(context, key))
            }
        }
    }

    /**
     * Notes whether root could be had, for the settings and for the rows alike; see [rootLost].
     * Whatever learns how root stands tells it here, so that the two never disagree.
     */
    internal fun setRootLost(context: Context, lost: Boolean) {
        Root.markLost(context, lost)
        _rootLost.value = lost
    }

    private fun readSaved(context: Context): Map<String, SavedApk> = SavedApks.parse(
        context.getSharedPreferences(PREFS_SAVED, Context.MODE_PRIVATE).getString(PREF_RECORDS, null)
    )

    /** Writes [records] down and shows them, as they are, for the rows to read. */
    private fun writeSaved(context: Context, records: Map<String, SavedApk>) {
        context.getSharedPreferences(PREFS_SAVED, Context.MODE_PRIVATE).edit {
            putString(PREF_RECORDS, SavedApks.toJson(records))
        }
        _saved.value = records
    }

    /**
     * Reads the files saved for the user, and lets go of those the user removed, see
     * [SavedApks.kept]. The files themselves stay: the newest of each app is kept in the folder
     * for the user to install again or to share, and the rows hide one whose version is
     * installed. Asks MediaStore about each, so not for the main thread. Nothing here may take
     * the app down: what cannot be read is read again the next time.
     */
    fun loadSaved(context: Context) {
        val appContext = context.applicationContext
        _rootLost.value = Root.enabled(appContext) && Root.lost(appContext)
        try {
            synchronized(savedLock) {
                val records = readSaved(appContext)
                val kept = SavedApks.kept(records) { ApkFolder.exists(appContext, it.uri) }
                if (kept != records) writeSaved(appContext, kept) else _saved.value = kept
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read the saved files", e)
        }
    }

    /**
     * Called by each of the store's screens as it comes into view: the files saved for the
     * user are read again, as one may have been installed or removed meanwhile. The records
     * of the install prompt of earlier versions are let go once.
     */
    internal fun onScreenStarted(context: Context) {
        screens.incrementAndGet()
        val appContext = context.applicationContext
        scope.launch {
            appContext.deleteSharedPreferences(OLD_PREFS_PENDING)
            loadSaved(appContext)
        }
    }

    internal fun onScreenStopped() {
        screens.decrementAndGet()
    }

    fun dismissFailure(repo: String) = _states.update { dismissed(it, repo) }

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
                    setRootLost(appContext, true)
                    withContext(Dispatchers.Main) { onNoRoot() }
                }
                is RootInstaller.Result.Done -> {
                    setRootLost(appContext, false)
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
}
