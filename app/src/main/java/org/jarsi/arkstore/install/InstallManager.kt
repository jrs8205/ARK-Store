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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jarsi.arkstore.data.ApkInfo
import org.jarsi.arkstore.data.CatalogRepository
import org.jarsi.arkstore.data.CatalogRules
import org.jarsi.arkstore.data.Http
import org.jarsi.arkstore.data.InstalledApps
import org.jarsi.arkstore.data.StoreApp

sealed interface InstallState {
    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(val progress: Float?) : InstallState
    data object Installing : InstallState
    data class Failed(val reason: FailReason, val detail: String? = null) : InstallState
}

enum class FailReason { DOWNLOAD, INVALID_APK, SIGNATURE_MISMATCH, INSTALL }

/** How an install that was waited for came out. */
enum class Outcome { INSTALLED, CONFIRMATION_NEEDED, FAILED }

/** Downloads release APKs and hands them to the system package installer. */
object InstallManager {
    private const val TAG = "InstallManager"

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
    private val committed = ConcurrentHashMap<String, Pair<StoreApp, String>>()

    private val _activeJobs = MutableStateFlow(0)
    /** How many downloads and installs are being worked on right now. */
    val activeJobs: StateFlow<Int> = _activeJobs.asStateFlow()

    private val _installedChanged = MutableStateFlow(0)
    /** Ticks whenever an install finishes, so observers re-read the installed versions. */
    val installedChanged: StateFlow<Int> = _installedChanged.asStateFlow()

    /** The outcome of each install as it is settled, for [installAndAwait]. */
    private val outcomes = MutableSharedFlow<Pair<String, Outcome>>(extraBufferCapacity = 64)

    fun isBusy(repo: String): Boolean = _states.value[repo].let {
        it is InstallState.Downloading || it is InstallState.Installing
    }

    /**
     * Downloads and installs [app]. From the [foreground], the download is kept going by a
     * service if the user leaves the app; from the background, where no service may start,
     * the caller keeps the process alive instead.
     */
    fun install(context: Context, app: StoreApp, foreground: Boolean = true) {
        val appContext = context.applicationContext
        if (isBusy(app.fullName)) return
        setState(app.fullName, InstallState.Downloading(0f))
        _activeJobs.update { it + 1 }
        if (foreground) InstallService.start(appContext)

        scope.launch {
            val target = File(File(appContext.cacheDir, "apk"), CatalogRules.downloadName(app.fullName))
            try {
                downloadAndInstall(appContext, app, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing here may take the process down or leave the app stuck as "installing".
                Log.w(TAG, "Install failed for ${app.fullName}", e)
                setState(app.fullName, InstallState.Failed(FailReason.INSTALL, e.message))
            } finally {
                // Once committed, the session holds its own copy of the file.
                target.delete()
                _activeJobs.update { it - 1 }
            }
        }
    }

    /**
     * [install]s [app] from the background and waits for the outcome: installed, waiting for
     * the user to confirm (a notification asks them), or failed. Null when nothing was
     * settled within [timeoutMillis]; the install itself goes on, and its outcome is handled
     * as any other's.
     */
    suspend fun installAndAwait(context: Context, app: StoreApp, timeoutMillis: Long): Outcome? = coroutineScope {
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
        try {
            var lastStep = -1
            Http.download(app.apkUrl, target) { read, total ->
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

        setState(app.fullName, InstallState.Installing)
        committed[app.fullName] = app to archive.packageName
        if (app.prerelease) InstalledApps.rememberBeta(appContext, archive.packageName, versionCode)
        try {
            commit(appContext, app.fullName, archive.packageName, target)
        } catch (e: Exception) {
            Log.w(TAG, "Install session failed for ${app.fullName}", e)
            committed.remove(app.fullName)
            setState(app.fullName, InstallState.Failed(FailReason.INSTALL, e.message))
        }
    }

    /** Called by each of the store's screens as it comes into view. */
    internal fun onScreenStarted() {
        screens.incrementAndGet()
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

    fun dismissFailure(repo: String) {
        if (_states.value[repo] is InstallState.Failed) setState(repo, null)
    }

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
                    InstalledApps.rememberConflict(context, attempted.first, attempted.second)
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

    private fun commit(context: Context, repo: String, packageName: String, apk: File) {
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
        try {
            write(context, installer, sessionId, repo, apk)
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
