package org.jarsi.arkstore.backup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.jarsi.arkstore.data.InstalledApps
import org.jarsi.arkstore.install.InstallManager
import org.jarsi.arkstore.install.Outcome
import org.jarsi.arkstore.install.localKey

/** An installed app a backup may take: its entry as the device tells it, and its files there. */
data class BackupCandidate(
    val entry: BackupEntry,
    /** Whether the files can be read; an archived app, for one, has none to copy. */
    val readable: Boolean,
    /** The files on the device, in the order of [BackupEntry.files]. */
    val sources: List<File>
)

/** What a backup or a restore is doing, for the screen and the notification. */
sealed interface BackupState {
    data object Idle : BackupState

    /** Working on app [index] of [total], named [label]; [bytesDone] of [bytesTotal] copied so far. */
    data class Running(
        val restoring: Boolean,
        val index: Int,
        val total: Int,
        val label: String,
        val bytesDone: Long,
        val bytesTotal: Long
    ) : BackupState

    /** Finished: [saved] apps copied or handed to the system, [skipped] found up to date, [failed] by name. */
    data class Done(val restoring: Boolean, val saved: Int, val skipped: Int, val failed: List<String>) : BackupState

    data class Failed(val restoring: Boolean, val reason: BackupError) : BackupState
}

enum class BackupError { FOLDER_UNAVAILABLE, CANCELLED }

/** An app a backup folder holds, as it stands against this device. */
data class RestoreEntry(
    val entry: BackupEntry,
    val status: RestoreStatus,
    /** The documents in the app's folder, by name; null when the folder is not there. */
    val files: Map<String, Doc>?
)

/** A backup folder as read: its apps, and the store's settings file when it has one. */
data class BackupFolder(
    val tree: Uri,
    val name: String,
    val entries: List<RestoreEntry>,
    val settings: Uri?
)

/**
 * Copies the installed apps' files to a folder of the user's choosing, and installs them back
 * from one; see [AppBackup] for the folder's form. One job at a time, watched through [state],
 * kept alive by [BackupService] while the user is elsewhere.
 */
object BackupManager {
    private const val TAG = "BackupManager"
    private const val PREFS = "backup"
    private const val PREF_TREE = "tree"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val JSON_MIME = "application/json"
    private const val BUFFER_BYTES = 64 * 1024
    /** The progress is told again after this much more has been copied. */
    private const val PROGRESS_STEP_BYTES = 1024 * 1024L
    /** How long an install is waited for before the restore goes on to the next app. */
    private const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()
    private var job: Job? = null
    private val restores = AtomicInteger(0)

    /**
     * The installed apps a backup may take, by name: the user's own apps, not the system's and
     * not the store itself. Asks the system about every app, so not for the main thread.
     */
    fun candidates(context: Context): List<BackupCandidate> {
        val packages = context.packageManager
        val self = context.packageName
        return InstalledApps.snapshot(context).values.mapNotNull { info ->
            val application = info.applicationInfo ?: return@mapNotNull null
            if (!AppBackup.isCandidate(application.flags, info.packageName, self)) return@mapNotNull null
            val base = File(application.sourceDir ?: return@mapNotNull null)
            val splits = application.splitSourceDirs?.map(::File).orEmpty()
            var names = splits.mapIndexed { i, file -> AppBackup.fileNameFor(file.name, i + 1) }
            // Two splits cannot share a name in the folder; if the device's names collide with
            // the made-up ones, the made-up ones are used throughout.
            if (names.toSet().size != names.size) names = splits.indices.map { "split_${it + 1}.apk" }
            val files = listOf(BackupFile(AppBackup.BASE_NAME, base.length())) +
                splits.mapIndexed { i, file -> BackupFile(names[i], file.length()) }
            val label = try {
                application.loadLabel(packages).toString().trim().ifEmpty { info.packageName }
            } catch (_: RuntimeException) {
                info.packageName
            }
            val entry = BackupEntry(
                packageName = info.packageName,
                label = label.take(AppBackup.MAX_LABEL),
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                versionName = info.versionName?.take(AppBackup.MAX_LABEL),
                installer = InstalledApps.installerOf(context, info.packageName, info.lastUpdateTime)
                    ?.takeIf(AppBackup::isPackageName),
                signers = InstalledApps.presentSigners(packages, info.packageName)
                    ?.sorted()?.take(AppBackup.MAX_SIGNERS).orEmpty(),
                updatedAt = info.lastUpdateTime,
                // A preview named by its codename comes out as CUR_DEVELOPMENT, which is no level.
                minSdk = application.minSdkVersion.takeIf { it in 1 until Build.VERSION_CODES.CUR_DEVELOPMENT },
                files = files
            )
            val sources = listOf(base) + splits
            BackupCandidate(entry, readable = sources.all { it.canRead() }, sources = sources)
        }.sortedWith(compareBy({ it.entry.label.lowercase() }, { it.entry.packageName }))
    }

    /**
     * Reads the folder [tree]: what its index lists, or failing an index, what its folders'
     * own entries say, each seen against what is installed here. Null when the folder cannot
     * be listed at all.
     */
    suspend fun read(context: Context, tree: Uri): BackupFolder? = withContext(Dispatchers.IO) {
        val docs = Documents(context.contentResolver, tree)
        val root = docs.children(docs.rootId) ?: return@withContext null
        val byName = root.associateBy { it.name }
        val folders = root.filter { it.isDirectory && AppBackup.isPackageName(it.name) }.associateBy { it.name }
        val index = byName[AppBackup.INDEX_NAME]
            ?.let { docs.readText(it.id, AppBackup.MAX_JSON_BYTES) }
            ?.let(AppBackup::indexFromText)
        val entries = index ?: folders.values.mapNotNull { folder ->
            docs.children(folder.id)
                ?.firstOrNull { it.name == AppBackup.ENTRY_NAME }
                ?.let { docs.readText(it.id, AppBackup.MAX_JSON_BYTES) }
                ?.let(AppBackup::entryFromText)
                ?.takeIf { it.packageName == folder.name }
        }
        val installed = InstalledApps.snapshot(context)
        val rows = entries.map { entry ->
            val files = folders[entry.packageName]
                ?.let { docs.children(it.id) }
                ?.filter { !it.isDirectory }
                ?.associateBy { it.name }
            RestoreEntry(entry, status(context, entry, files, installed), files)
        }.sortedWith(compareBy({ it.entry.label.lowercase() }, { it.entry.packageName }))
        BackupFolder(
            tree,
            docs.name() ?: tree.lastPathSegment.orEmpty(),
            rows,
            byName[AppBackup.SETTINGS_NAME]?.takeIf { !it.isDirectory }?.let { docs.uri(it.id) }
        )
    }

    /** [folder] seen against what is installed now; the folder itself is not read again. */
    suspend fun refresh(context: Context, folder: BackupFolder): BackupFolder = withContext(Dispatchers.IO) {
        val installed = InstalledApps.snapshot(context)
        folder.copy(entries = folder.entries.map { it.copy(status = status(context, it.entry, it.files, installed)) })
    }

    private fun status(
        context: Context,
        entry: BackupEntry,
        files: Map<String, Doc>?,
        installed: Map<String, PackageInfo>
    ): RestoreStatus {
        val info = installed[entry.packageName]
        return RestoreRules.status(
            entry,
            installedVersion = info?.let(PackageInfoCompat::getLongVersionCode),
            keysHeld = if (info != null) InstalledApps.keysHeld(context.packageManager, entry.packageName) else null,
            present = files?.mapValues { it.value.size },
            sdk = Build.VERSION.SDK_INT
        )
    }

    /**
     * Copies [selected] to the folder [tree], along with the store's settings as [settingsJson];
     * returns whether the job was begun, which it is not while another is running. The apps
     * whose copies are up to date are left as they are, see [AppBackup.isUpToDate].
     */
    fun backup(context: Context, tree: Uri, selected: List<BackupCandidate>, settingsJson: String): Boolean {
        val appContext = context.applicationContext
        return start(appContext, restoring = false) {
            write(Documents(appContext.contentResolver, tree), selected, settingsJson)
        }
    }

    /** Installs [selected] from [folder], one after the other; returns whether the job was begun. */
    fun restore(context: Context, folder: BackupFolder, selected: List<RestoreEntry>): Boolean {
        val appContext = context.applicationContext
        return start(appContext, restoring = true) {
            install(appContext, Documents(appContext.contentResolver, folder.tree), selected)
        }
    }

    /** Stops the job after the file it is on; the folder's index is still brought up to date. */
    fun cancel() {
        job?.cancel()
    }

    /** Puts a finished or failed job out of sight. */
    fun dismiss() {
        _state.update { if (it is BackupState.Running) it else BackupState.Idle }
    }

    /**
     * Keeps the right to [tree] across restarts, lets go of any folder kept before, and
     * remembers it as the folder to offer next time.
     */
    fun rememberTree(context: Context, tree: Uri) {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.persistedUriPermissions.filter { it.uri != tree }.forEach {
            runCatching { resolver.releasePersistableUriPermission(it.uri, flags) }
        }
        runCatching { resolver.takePersistableUriPermission(tree, flags) }
            .onFailure { Log.w(TAG, "Could not keep the right to the folder", it) }
        prefs(context).edit { putString(PREF_TREE, tree.toString()) }
    }

    /** The folder remembered, when the right to it is still held. */
    fun rememberedTree(context: Context): Uri? {
        val uri = prefs(context).getString(PREF_TREE, null)?.let(Uri::parse) ?: return null
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return if (held && DocumentsContract.isTreeUri(uri)) uri else null
    }

    @Synchronized
    private fun start(context: Context, restoring: Boolean, work: suspend () -> BackupState): Boolean {
        if (job?.isActive == true) return false
        _state.value = BackupState.Running(restoring, 0, 0, "", 0, 0)
        BackupService.start(context)
        job = scope.launch {
            _state.value = try {
                work()
            } catch (_: CancellationException) {
                BackupState.Failed(restoring, BackupError.CANCELLED)
            } catch (e: Exception) {
                Log.w(TAG, if (restoring) "Restore failed" else "Backup failed", e)
                BackupState.Failed(restoring, BackupError.FOLDER_UNAVAILABLE)
            }
        }
        return true
    }

    private suspend fun write(docs: Documents, selected: List<BackupCandidate>, settingsJson: String): BackupState {
        val root = docs.children(docs.rootId) ?: throw IOException("The folder cannot be listed")
        val rootByName = root.associateBy { it.name }.toMutableMap()
        val old = rootByName[AppBackup.INDEX_NAME]
            ?.let { docs.readText(it.id, AppBackup.MAX_JSON_BYTES) }
            ?.let(AppBackup::indexFromText)
            .orEmpty()
        val total = selected.sumOf { it.entry.size }
        var done = 0L
        var saved = 0
        var skipped = 0
        val failed = ArrayList<String>()
        val written = ArrayList<BackupEntry>()
        try {
            selected.forEachIndexed { i, candidate ->
                coroutineContext.ensureActive()
                val entry = candidate.entry
                _state.value = BackupState.Running(false, i + 1, selected.size, entry.label, done, total)
                try {
                    val folderId = rootByName[entry.packageName]?.takeIf { it.isDirectory }?.id
                        ?: docs.createDirectory(docs.rootId, entry.packageName).also {
                            rootByName[entry.packageName] = Doc(it, entry.packageName, DocumentsContract.Document.MIME_TYPE_DIR, -1)
                        }
                    val children = docs.children(folderId) ?: throw IOException("The app's folder cannot be listed")
                    val byName = children.filter { !it.isDirectory }.associateBy { it.name }.toMutableMap()
                    val stored = byName[AppBackup.ENTRY_NAME]
                        ?.let { docs.readText(it.id, AppBackup.MAX_JSON_BYTES) }
                        ?.let(AppBackup::entryFromText)
                    if (AppBackup.isUpToDate(stored, byName.mapValues { it.value.size }, entry)) {
                        skipped++
                        written += entry
                        done += entry.size
                        return@forEachIndexed
                    }
                    // The entry goes first and comes back last: a folder without one is a copy
                    // cut short, and is copied again next time. Files of an earlier version that
                    // are not among this one's go too.
                    byName.remove(AppBackup.ENTRY_NAME)?.let { docs.delete(it.id) }
                    val names = entry.files.mapTo(HashSet()) { it.name }
                    byName.values.filter { it.name.endsWith(".apk") && it.name !in names }.forEach {
                        docs.delete(it.id)
                        byName.remove(it.name)
                    }
                    entry.files.forEachIndexed { f, file ->
                        val id = byName[file.name]?.id ?: docs.create(folderId, APK_MIME, file.name)
                        candidate.sources[f].inputStream().use { input ->
                            docs.openWrite(id).use { output ->
                                done += copy(input, output) { copied ->
                                    _state.value = BackupState.Running(false, i + 1, selected.size, entry.label, done + copied, total)
                                }
                            }
                        }
                    }
                    docs.writeText(docs.create(folderId, JSON_MIME, AppBackup.ENTRY_NAME), entry.toJson().toString(2))
                    saved++
                    written += entry
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not back up ${entry.packageName}", e)
                    failed += entry.label
                }
            }
            return BackupState.Done(false, saved, skipped, failed)
        } finally {
            // What the folder holds now is written down even after a cancel, so that a restore
            // finds every app that was copied whole.
            withContext(NonCancellable) {
                try {
                    val folders = (docs.children(docs.rootId) ?: root).filter { it.isDirectory }.mapTo(HashSet()) { it.name }
                    val index = AppBackup.mergedIndex(old, folders, written)
                    writeRoot(docs, rootByName, AppBackup.INDEX_NAME, AppBackup.indexToJson(index, Instant.now().toString()).toString(2))
                    writeRoot(docs, rootByName, AppBackup.SETTINGS_NAME, settingsJson)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not write the folder's index", e)
                }
            }
        }
    }

    private fun writeRoot(docs: Documents, rootByName: Map<String, Doc>, name: String, text: String) {
        val id = rootByName[name]?.takeIf { !it.isDirectory }?.id ?: docs.create(docs.rootId, JSON_MIME, name)
        docs.writeText(id, text)
    }

    private suspend fun install(context: Context, docs: Documents, selected: List<RestoreEntry>): BackupState {
        val total = selected.sumOf { it.entry.size }
        var done = 0L
        var saved = 0
        val failed = ArrayList<String>()
        selected.forEachIndexed { i, row ->
            coroutineContext.ensureActive()
            val entry = row.entry
            _state.value = BackupState.Running(true, i + 1, selected.size, entry.label, done, total)
            // A folder of this app's own in the cache; the install deletes it once it is settled.
            val dir = File(File(context.cacheDir, "apk"), "restore-${restores.incrementAndGet()}")
            try {
                val files = row.files ?: throw IOException("The app's folder is gone")
                if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Could not make room for the files")
                val copied = entry.files.map { file ->
                    val doc = files[file.name] ?: throw IOException("${file.name} is gone")
                    val target = File(dir, file.name)
                    docs.openRead(doc.id).use { input ->
                        target.outputStream().use { output ->
                            done += copy(input, output) { bytes ->
                                _state.value = BackupState.Running(true, i + 1, selected.size, entry.label, done + bytes, total)
                            }
                        }
                    }
                    target
                }
                val key = localKey(entry.packageName)
                val outcome = InstallManager.installLocalAndAwait(
                    context, key, entry.label, entry.packageName, dir, copied, INSTALL_TIMEOUT_MS
                )
                when (outcome) {
                    Outcome.INSTALLED, Outcome.CONFIRMATION_NEEDED -> saved++
                    Outcome.FAILED, Outcome.CANCELLED -> failed += entry.label
                    // Nothing settled: an install of the package was already on its way, which
                    // never took these files, or the system has not answered in all this time.
                    null -> if (!InstallManager.isBusy(key)) dir.deleteRecursively()
                }
            } catch (e: CancellationException) {
                dir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore ${entry.packageName}", e)
                failed += entry.label
                dir.deleteRecursively()
            }
        }
        return BackupState.Done(true, saved, 0, failed)
    }

    /**
     * Copies [input] to [output] and returns the bytes copied, telling [onProgress] the count
     * so far every [PROGRESS_STEP_BYTES]; stops with a [CancellationException] when the job
     * is cancelled, which the streams would not notice on their own.
     */
    private suspend fun copy(input: InputStream, output: OutputStream, onProgress: (Long) -> Unit): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var copied = 0L
        var lastTold = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            copied += n
            if (copied - lastTold >= PROGRESS_STEP_BYTES) {
                lastTold = copied
                onProgress(copied)
            }
        }
        output.flush()
        return copied
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
