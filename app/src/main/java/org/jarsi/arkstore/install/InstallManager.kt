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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jarsi.arkstore.data.ApkInfo
import org.jarsi.arkstore.data.CatalogRepository
import org.jarsi.arkstore.data.Http
import org.jarsi.arkstore.data.StoreApp

sealed interface InstallState {
    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(val progress: Float?) : InstallState
    data object Installing : InstallState
    data class Failed(val reason: FailReason, val detail: String? = null) : InstallState
}

enum class FailReason { DOWNLOAD, INVALID_APK, SIGNATURE_MISMATCH, INSTALL }

/** Downloads release APKs and hands them to the system package installer. */
object InstallManager {
    private const val TAG = "InstallManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    /** In-flight or failed installs keyed by the repository's full name. */
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()

    internal val confirmations = InstallConfirmationQueue<Intent>()

    private val _activeJobs = MutableStateFlow(0)
    /** How many downloads and installs are being worked on right now. */
    val activeJobs: StateFlow<Int> = _activeJobs.asStateFlow()

    private val _installedChanged = MutableStateFlow(0)
    /** Ticks whenever an install finishes, so observers re-read the installed versions. */
    val installedChanged: StateFlow<Int> = _installedChanged.asStateFlow()

    fun isBusy(repo: String): Boolean = _states.value[repo].let {
        it is InstallState.Downloading || it is InstallState.Installing
    }

    fun install(context: Context, app: StoreApp) {
        val appContext = context.applicationContext
        if (isBusy(app.fullName)) return
        setState(app.fullName, InstallState.Downloading(0f))
        _activeJobs.update { it + 1 }
        // Lets the download carry on if the user leaves the app before it is done.
        InstallService.start(appContext)

        scope.launch {
            val target = File(File(appContext.cacheDir, "apk"), "${app.fullName.replace('/', '_')}.apk")
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

        val archive = readArchive(appContext, target)
        if (archive == null ||
            (app.packageName != null && archive.packageName != app.packageName)
        ) {
            setState(app.fullName, InstallState.Failed(FailReason.INVALID_APK))
            return
        }
        try {
            CatalogRepository.get(appContext).rememberApkInfo(
                app.fullName,
                app.assetId,
                ApkInfo(
                    archive.packageName,
                    PackageInfoCompat.getLongVersionCode(archive),
                    archive.versionName
                )
            )
        } catch (e: IOException) {
            // Only a convenience for the next refresh; the install itself does not need it.
            Log.w(TAG, "Could not store APK details for ${app.fullName}", e)
        }
        if (!signaturesMatch(appContext, archive)) {
            setState(app.fullName, InstallState.Failed(FailReason.SIGNATURE_MISMATCH))
            return
        }

        setState(app.fullName, InstallState.Installing)
        try {
            commit(appContext, app.fullName, archive.packageName, target)
        } catch (e: Exception) {
            Log.w(TAG, "Install session failed for ${app.fullName}", e)
            setState(app.fullName, InstallState.Failed(FailReason.INSTALL, e.message))
        }
    }

    fun dismissFailure(repo: String) {
        if (_states.value[repo] is InstallState.Failed) setState(repo, null)
    }

    internal fun onConfirmationRequired(repo: String, intent: Intent) {
        setState(repo, InstallState.Installing)
        confirmations.enqueue(repo, intent)
    }

    /** Called by [InstallReceiver] once the system has decided on a session. */
    internal fun onSessionResult(repo: String, status: Int, message: String?) {
        confirmations.remove(repo)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> setState(repo, null)
            // The user backed out of the system prompt; that is not an error worth showing.
            PackageInstaller.STATUS_FAILURE_ABORTED -> setState(repo, null)
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                setState(repo, InstallState.Failed(conflictReason(message), message))
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

    private fun setState(repo: String, state: InstallState?) = _states.update {
        if (state == null) it - repo else it + (repo to state)
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

    /** False only when the app is installed and provably signed with a different key. */
    @Suppress("DEPRECATION")
    private fun signaturesMatch(context: Context, archive: PackageInfo): Boolean {
        val installed = try {
            context.packageManager.getPackageInfo(archive.packageName, PackageManager.GET_SIGNATURES)
        } catch (_: PackageManager.NameNotFoundException) {
            return true
        }
        val installedSigners = installed.signatures?.map { digest(it.toByteArray()) }.orEmpty()
        val archiveSigners = archive.signatures?.map { digest(it.toByteArray()) }.orEmpty()
        if (installedSigners.isEmpty() || archiveSigners.isEmpty()) return true
        return archiveSigners.any { it in installedSigners }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
