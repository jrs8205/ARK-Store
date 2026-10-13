package org.jarsi.arkstore.backup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.SecretKey
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
import org.jarsi.arkstore.install.Root
import org.jarsi.arkstore.install.localKey

/** An installed app a backup may take: its entry as the device tells it, and its files there. */
data class BackupCandidate(
    val entry: BackupEntry,
    /** Whether the files can be read; an archived app, for one, has none to copy. */
    val readable: Boolean,
    /** The files on the device, in the order of [BackupEntry.files]. */
    val sources: List<File>,
    /** About how much the app's own data is, in bytes, when root has told; see [BackupManager.dataSizes]. */
    val dataSize: Long? = null
)

/**
 * Whether a backup takes the apps' own data as root, or a restore puts it back, and with
 * what [password]: none, or an empty one, for data written in the clear.
 */
class DataChoice(val include: Boolean, val password: CharArray?) {
    val encrypt: Boolean get() = include && password != null && password.isNotEmpty()

    companion object {
        val NONE = DataChoice(false, null)
    }
}

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

    /**
     * Finished: [saved] apps copied or handed to the system, [skipped] found up to date, [failed]
     * by name; [indexWritten] false when the apps were copied but the folder's index and the
     * settings file could not be written, so that a restore has to read each app's folder.
     */
    data class Done(
        val restoring: Boolean,
        val saved: Int,
        val skipped: Int,
        val failed: List<String>,
        val indexWritten: Boolean = true,
        /** The backup was a zip file rather than a folder; [failed] are then the apps left out of it. */
        val zip: Boolean = false,
        /** The apps whose files went through but whose own data did not, by name. */
        val dataFailed: List<String> = emptyList(),
        /** Some data could not be put back because the password was not the one it was encrypted with. */
        val wrongPassword: Boolean = false
    ) : BackupState

    data class Failed(
        val restoring: Boolean,
        val reason: BackupError,
        val indexWritten: Boolean = true,
        val zip: Boolean = false
    ) : BackupState
}

enum class BackupError { FOLDER_UNAVAILABLE, CANCELLED }

/** An app a backup folder holds, as it stands against this device. */
data class RestoreEntry(
    val entry: BackupEntry,
    val status: RestoreStatus,
    /** The documents in the app's folder, by name; null when the folder is not there. */
    val files: Map<String, Doc>?
)

/**
 * A backup as read: its apps, and the store's settings file when it has one. [tree] is the
 * folder, or the zip file when [zip]; the files of a zip's apps are named by their place in it.
 */
data class BackupFolder(
    val tree: Uri,
    val name: String,
    val entries: List<RestoreEntry>,
    val settings: Uri?,
    val zip: Boolean = false
)

/**
 * Copies the installed apps' files to a folder of the user's choosing or to a zip file, and
 * installs them back from either; see [AppBackup] for the folder's form and [ZipBackup] for the
 * zip's. One job at a time, watched through [state],
 * kept alive by [BackupService] while the user is elsewhere.
 */
object BackupManager {
    private const val TAG = "BackupManager"
    private const val PREFS = "backup"
    private const val PREF_TREE = "tree"
    private const val PREF_LOG = "log"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val JSON_MIME = "application/json"
    private const val DATA_MIME = "application/octet-stream"
    /** Where the settings of a zip read for a restore are kept for their import. */
    private const val ZIP_SETTINGS_DIR = "backup-settings"
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

    /** About how much data each of [packages] has, in bytes, as root tells it; nothing without root. */
    suspend fun dataSizes(packages: List<String>): Map<String, Long> = RootData.sizes(packages)

    /** Whether the apps' data can be taken: root is on and the device has a `tar`. */
    suspend fun dataAvailable(context: Context): Boolean = Root.enabled(context) && RootData.tar() != null

    /**
     * Reads the folder [tree]: each app folder's own entry, which is written last and so
     * stands for a whole copy, seen against what is installed here. The folder's index is not
     * what is read: it may be older than the folders, as when it could not be written. Null
     * when the folder cannot be listed at all.
     */
    suspend fun read(context: Context, tree: Uri): BackupFolder? = withContext(Dispatchers.IO) {
        val docs = Documents(context.contentResolver, tree)
        val root = docs.children(docs.rootId) ?: return@withContext null
        val byName = root.associateBy { it.name }
        val folders = root.filter { it.isDirectory && AppBackup.isPackageName(it.name) }.take(AppBackup.MAX_ENTRIES)
        val installed = InstalledApps.snapshot(context)
        val rows = folders.mapNotNull { folder ->
            val files = docs.children(folder.id)?.filter { !it.isDirectory }?.associateBy { it.name }
                ?: return@mapNotNull null
            val entry = files[AppBackup.ENTRY_NAME]
                ?.let { docs.readText(it.id, AppBackup.MAX_JSON_BYTES) }
                ?.let(AppBackup::entryFromText)
                ?.takeIf { it.packageName == folder.name }
                ?: return@mapNotNull null
            RestoreEntry(entry, status(context, entry, files, installed), files)
        }.sortedWith(compareBy({ it.entry.label.lowercase() }, { it.entry.packageName }))
        BackupFolder(
            tree,
            docs.name() ?: tree.lastPathSegment.orEmpty(),
            rows,
            byName[AppBackup.SETTINGS_NAME]?.takeIf { !it.isDirectory }?.let { docs.uri(it.id) }
        )
    }

    /**
     * Reads the zip file [uri]: the apps its head tells, seen against what is installed here, and
     * its settings, kept in the cache for an import. Null when the file cannot be read at all.
     */
    suspend fun readZip(context: Context, uri: Uri): BackupFolder? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val head = try {
            resolver.openInputStream(uri)?.use { ZipBackup.readHead(BufferedInputStream(it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read a zip file", e)
            null
        } ?: return@withContext null
        // A file of this read's own: a read given up meanwhile must not overwrite the next one's.
        File(context.cacheDir, ZIP_SETTINGS_DIR).listFiles()?.forEach { it.delete() }
        val settings = head.settings?.let { text ->
            try {
                val dir = File(context.cacheDir, ZIP_SETTINGS_DIR).apply { mkdirs() }
                File(dir, "settings-${restores.incrementAndGet()}.json").apply { writeText(text) }.let(Uri::fromFile)
            } catch (e: IOException) {
                Log.w(TAG, "Could not keep the zip file's settings", e)
                null
            }
        }
        val installed = InstalledApps.snapshot(context)
        val rows = head.entries.map { entry ->
            val files = zipFiles(entry)
            RestoreEntry(entry, status(context, entry, files, installed), files)
        }.sortedWith(compareBy({ it.entry.label.lowercase() }, { it.entry.packageName }))
        BackupFolder(uri, displayName(context, uri) ?: uri.lastPathSegment.orEmpty(), rows, settings, zip = true)
    }

    /**
     * The files of [entry] in a zip, as its head tells them: a zip written whole holds them all,
     * and one that does not is found out when its files are taken out.
     */
    private fun zipFiles(entry: BackupEntry): Map<String, Doc> =
        (entry.files.map { Doc("${entry.packageName}/${it.name}", it.name, APK_MIME, it.size) } +
            Doc("${entry.packageName}/${AppBackup.ENTRY_NAME}", AppBackup.ENTRY_NAME, JSON_MIME, RestoreRules.UNKNOWN_SIZE))
            .associateBy { it.name }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
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
            // A folder without its entry is a copy cut short, whatever the index says of it.
            present = files?.takeIf { AppBackup.ENTRY_NAME in it }?.mapValues { it.value.size },
            sdk = Build.VERSION.SDK_INT
        )
    }

    /**
     * Copies [selected] to the folder [tree], along with the store's settings as [settingsJson];
     * returns whether the job was begun, which it is not while another is running. The apps
     * whose copies are up to date are left as they are, see [AppBackup.isUpToDate].
     */
    fun backup(
        context: Context,
        tree: Uri,
        selected: List<BackupCandidate>,
        settingsJson: String,
        data: DataChoice = DataChoice.NONE
    ): Boolean {
        val appContext = context.applicationContext
        val told = Told(BackupEvent.Kind.FOLDER, selected.size, candidateBytes(selected, data)) {
            Documents(appContext.contentResolver, tree).name()
        }
        return start(appContext, restoring = false, told = told) {
            write(Documents(appContext.contentResolver, tree), selected, settingsJson, data)
        }
    }

    /**
     * Writes [selected] to the zip file [uri], made for it, along with the store's settings as
     * [settingsJson]; returns whether the job was begun. A zip that cannot be finished is removed.
     */
    fun backupZip(
        context: Context,
        uri: Uri,
        selected: List<BackupCandidate>,
        settingsJson: String,
        data: DataChoice = DataChoice.NONE
    ): Boolean {
        val appContext = context.applicationContext
        val told = Told(BackupEvent.Kind.ZIP, selected.size, candidateBytes(selected, data)) {
            displayName(appContext, uri)
        }
        return start(appContext, restoring = false, zip = true, told = told) {
            writeZip(appContext, uri, selected, settingsJson, data)
        }
    }

    /**
     * Installs [selected] from [folder], one after the other, and puts their data back when
     * [data] says so and the backup has it; returns whether the job was begun.
     */
    fun restore(
        context: Context,
        folder: BackupFolder,
        selected: List<RestoreEntry>,
        data: DataChoice = DataChoice.NONE
    ): Boolean {
        val appContext = context.applicationContext
        val bytes = selected.sumOf { it.entry.size + if (data.include) it.entry.dataSize else 0 }
        val told = Told(BackupEvent.Kind.RESTORE, selected.size, bytes) { folder.name }
        return start(appContext, restoring = true, zip = folder.zip, told = told) {
            if (folder.zip) {
                installZip(appContext, folder.tree, selected, data)
            } else {
                install(appContext, Documents(appContext.contentResolver, folder.tree), selected, data)
            }
        }
    }

    private fun candidateBytes(selected: List<BackupCandidate>, data: DataChoice): Long =
        selected.sumOf { it.entry.size + if (data.include) it.dataSize ?: 0 else 0 }

    /**
     * What a run that takes or puts back the apps' data works with: the device's `tar`, and
     * the keys, each derived once, since deriving one is slow by design. Null when the data
     * is not asked for, or cannot be had.
     */
    private class DataRun(val tar: String, private val password: CharArray?) {
        val encrypt: Boolean get() = password != null && password.isNotEmpty()

        /** One salt for the run: every archive written in it is encrypted with the same key. */
        private val salt = DataCrypt.newSalt()
        private var writeKey: SecretKey? = null
        private val keys = HashMap<String, SecretKey>()

        /**
         * [sink] with the packing and, with a password, the encryption in front of it, the
         * archive bound to [entry]'s package and signers.
         */
        fun wrap(sink: OutputStream, entry: BackupEntry): OutputStream {
            val encrypted = if (encrypt) {
                val key = writeKey ?: DataCrypt.deriveKey(password!!, salt, DataCrypt.ITERATIONS).also { writeKey = it }
                DataCrypt.encrypt(sink, key, salt, bound = DataBinding.text(entry.packageName, entry.signers))
            } else {
                sink
            }
            return GZIPOutputStream(encrypted, BUFFER_BYTES)
        }

        /**
         * [input], an archive as written by [wrap], unpacked and, when [encrypted], decrypted:
         * the plain data, and the text an encrypted archive is bound to (null for one in the
         * clear). The text is checked before anything is read, and vouched for by the first chunk.
         */
        fun unwrap(input: InputStream, encrypted: Boolean): Pair<InputStream, String?> {
            if (!encrypted) return GZIPInputStream(input, BUFFER_BYTES) to null
            val decrypted = DataCrypt.decrypt(input, ::keyFor)
            return Whole(GZIPInputStream(decrypted, BUFFER_BYTES), decrypted) to decrypted.bound
        }

        private fun keyFor(salt: ByteArray, iterations: Int): SecretKey = synchronized(keys) {
            keys.getOrPut(salt.joinToString("") { "%02x".format(it) } + ":" + iterations) {
                DataCrypt.deriveKey(password ?: CharArray(0), salt, iterations)
            }
        }
    }

    /** The run for [data], or null when the data is not asked for or there is no `tar` to take it with. */
    private suspend fun dataRun(data: DataChoice): DataRun? {
        if (!data.include) return null
        val tar = RootData.tar() ?: return null
        return DataRun(tar, data.password)
    }

    /**
     * [plain], the archive unpacked from [encrypted]: at its end, the encrypted file is read
     * to its own end as well, which the unpacking need not have reached, so that a file whose
     * last chunk is missing or altered is refused before anything is done with the archive.
     */
    private class Whole(private val plain: InputStream, private val encrypted: DataCrypt.Decrypted) : InputStream() {
        private var checked = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = plain.read(b, off, len)
            if (n < 0 && !checked) {
                checked = true
                val rest = ByteArray(BUFFER_BYTES)
                while (encrypted.read(rest) >= 0) {
                    // Whatever is left holds nothing of the archive; the chunks are opened all the same.
                }
                if (!encrypted.whole) throw IOException("The encrypted data was not read to its end")
            }
            return n
        }

        override fun close() = plain.close()
    }

    /** Counts what passes through to [out]. */
    private class Counting(private val out: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) {
            out.write(b)
            count++
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    /**
     * Puts the data archives of [entry] back, each [open]ed by its description, into the app
     * that is now installed. Every archive is unpacked and checked before any is put in place,
     * so that the app is left with the data it had, never with one part from the backup and
     * another of its own. Returns null when all went through, otherwise whether it was the
     * password that was wrong.
     */
    private suspend fun restoreData(
        context: Context,
        run: DataRun,
        entry: BackupEntry,
        open: (BackupData) -> InputStream?
    ): Boolean? {
        val uid = try {
            context.packageManager.getApplicationInfo(entry.packageName, 0).uid
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "${entry.packageName} is not installed, so its data is not put back")
            return false
        }
        // The data goes only into the app it was taken from: the one installed now must be
        // signed by whom the archive says, so that files swapped beside it in the backup get
        // nothing.
        val installedSigners = InstalledApps.presentSigners(context.packageManager, entry.packageName)
        val staged = ArrayList<RootData.Staged>(entry.data.size)
        try {
            for (archive in entry.data) {
                val input = open(archive)
                if (input == null) {
                    Log.w(TAG, "The archive ${archive.name} of ${entry.packageName} is not there")
                    return false
                }
                try {
                    val (tar, bound) = try {
                        run.unwrap(input, archive.encrypted)
                    } catch (e: Throwable) {
                        input.close()
                        throw e
                    }
                    tar.use {
                        if (!RestoreRules.dataAllowed(entry.packageName, bound, entry.signers, installedSigners)) {
                            Log.w(TAG, "The archive ${archive.name} is not bound to the ${entry.packageName} installed now")
                            return false
                        }
                        staged += RootData.stage(run.tar, archive.part, entry.packageName, it)
                    }
                } catch (e: WrongPasswordException) {
                    Log.w(TAG, "The password does not open ${archive.name} of ${entry.packageName}")
                    return true
                }
            }
            RootData.swap(staged, entry.packageName, uid)
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not put back the data of ${entry.packageName}", e)
            return false
        } finally {
            RootData.discard(staged)
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

    /** What a job is, for the log of [history]: its kind, how many apps of what size, and where. */
    private class Told(val kind: BackupEvent.Kind, val apps: Int, val bytes: Long, val place: suspend () -> String?)

    @Synchronized
    private fun start(
        context: Context,
        restoring: Boolean,
        zip: Boolean = false,
        told: Told,
        work: suspend () -> BackupState
    ): Boolean {
        if (job?.isActive == true) return false
        _state.value = BackupState.Running(restoring, 0, 0, "", 0, 0)
        BackupService.start(context)
        job = scope.launch {
            val place = try {
                told.place()
            } catch (e: CancellationException) {
                null
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the place's name", e)
                null
            }.orEmpty()
            val end = try {
                work()
            } catch (e: CancelledWith) {
                BackupState.Failed(restoring, BackupError.CANCELLED, e.indexWritten, zip)
            } catch (_: CancellationException) {
                BackupState.Failed(restoring, BackupError.CANCELLED, zip = zip)
            } catch (e: Exception) {
                Log.w(TAG, if (restoring) "Restore failed" else "Backup failed", e)
                BackupState.Failed(restoring, BackupError.FOLDER_UNAVAILABLE, zip = zip)
            }
            // Written down before it is shown, so that the settings read it with the outcome.
            withContext(NonCancellable) {
                val event = BackupLog.event(told.kind, System.currentTimeMillis(), place, told.apps, told.bytes, end)
                prefs(context).edit { putString(PREF_LOG, BackupLog.toText(BackupLog.added(history(context), event))) }
            }
            _state.value = end
        }
        return true
    }

    /** The last backups and restores on this device, newest first. */
    fun history(context: Context): List<BackupEvent> = BackupLog.fromText(prefs(context).getString(PREF_LOG, null))

    /**
     * Writes the zip: the apps whose files are still those of the list, the rest left out and
     * named, since an app updated since the list was read has other files than its entry says.
     */
    private suspend fun writeZip(
        context: Context,
        uri: Uri,
        selected: List<BackupCandidate>,
        settingsJson: String,
        data: DataChoice
    ): BackupState {
        val (whole, changed) = selected.partition { candidate ->
            candidate.readable && candidate.sources.size == candidate.entry.files.size &&
                candidate.sources.zip(candidate.entry.files).all { (file, said) -> file.canRead() && file.length() == said.size }
        }
        val byPackage = whole.associateBy { it.entry.packageName }
        val total = candidateBytes(whole, data)
        var index = 0
        var label = ""
        val dataFailed = ArrayList<String>()
        try {
            // The picker has made the file already; one with nothing to put in it goes too.
            if (whole.isEmpty()) throw IOException("None of the apps chosen can be written as they are")
            // The zip's head names the data archives before they are written, so what each app
            // has is asked first; their sizes stay unknown to the head.
            val run = dataRun(data)
            // No run, or a listing that could not be had whole: none of the data goes in,
            // and every app is named for it.
            val parts = run?.let { RootData.parts(whole.map { it.entry.packageName }) }
            if (data.include && parts == null) dataFailed += whole.map { it.entry.label }
            val entries = whole.map { candidate ->
                candidate.entry.copy(
                    data = parts?.get(candidate.entry.packageName).orEmpty().map {
                        BackupData(DataCrypt.fileName(it, run!!.encrypt), RestoreRules.UNKNOWN_SIZE)
                    }
                )
            }
            var filesDone = 0L
            var dataDone = 0L
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Nothing to write to")
            out.use { stream ->
                ZipBackup.write(
                    BufferedOutputStream(stream, BUFFER_BYTES),
                    entries,
                    settingsJson,
                    Instant.now().toString(),
                    open = { entry, i ->
                        if (i == 0) {
                            index++
                            label = entry.label
                        }
                        byPackage.getValue(entry.packageName).sources[i].inputStream()
                    },
                    onBytes = { bytes ->
                        filesDone = bytes
                        _state.value = BackupState.Running(false, index, whole.size, label, filesDone + dataDone, total)
                    },
                    writeData = { entry, archive, item ->
                        val sink = run!!.wrap(item, entry)
                        try {
                            val bytes = RootData.archive(run.tar, archive.part, entry.packageName, sink) { sofar ->
                                _state.value = BackupState.Running(false, index, whole.size, label, filesDone + dataDone + sofar, total)
                            }
                            // Closing writes the end of the packing and the last chunk of the
                            // encryption, without which a restore refuses the archive: an
                            // archive not taken whole is left without them.
                            sink.close()
                            dataDone += bytes
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // The item stands, cut short; a restore finds it so and leaves the data be.
                            Log.w(TAG, "Could not take the data of ${entry.packageName}", e)
                            if (entry.label !in dataFailed) dataFailed += entry.label
                        }
                    }
                )
            }
        } catch (e: Throwable) {
            // A zip cut short would tell of apps it does not hold; it goes.
            withContext(NonCancellable) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    .onFailure { Log.w(TAG, "Could not remove the unfinished zip file", it) }
            }
            throw e
        }
        return BackupState.Done(false, whole.size, 0, changed.map { it.entry.label }, zip = true, dataFailed = dataFailed)
    }

    /**
     * Installs [selected] from the zip file [uri], taking each app's files out as the zip comes
     * to them, and handing them to the system as [install] does for a folder.
     */
    private suspend fun installZip(context: Context, uri: Uri, selected: List<RestoreEntry>, data: DataChoice): BackupState {
        val total = selected.sumOf { it.entry.size + if (data.include) it.entry.dataSize else 0 }
        var index = 0
        var label = ""
        var saved = 0
        val failed = ArrayList<String>()
        val dataFailed = ArrayList<String>()
        var wrongPassword = false
        val run = dataRun(data)
        // Without a run the data archives are not wanted: left out of what is asked of the
        // zip, they are passed over rather than taken out, however large they are.
        val asked = selected.map { if (run == null) it.entry.copy(data = emptyList()) else it.entry }
        val hadData = selected.associate { it.entry.packageName to it.entry.data.isNotEmpty() }
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("The zip file cannot be read")
        // The data archives go beside the files, not with them: the install takes the files'
        // folder and removes it when it settles, before the data is put back.
        val dataDirs = HashMap<String, File>()
        try {
            input.use { stream ->
                ZipBackup.extract(
                    BufferedInputStream(stream, BUFFER_BYTES),
                    asked,
                    dirFor = { entry ->
                        index++
                        label = entry.label
                        File(File(context.cacheDir, "apk"), "restore-${restores.incrementAndGet()}")
                    },
                    dataDirFor = { entry ->
                        dataDirs.getOrPut(entry.packageName) {
                            File(File(context.cacheDir, "apk"), "restore-data-${restores.incrementAndGet()}")
                        }
                    },
                    onBytes = { bytes -> _state.value = BackupState.Running(true, index, selected.size, label, bytes, total) },
                    onApp = { entry, files, archives ->
                        try {
                            if (files == null) {
                                Log.w(TAG, "The zip file does not hold the files of ${entry.packageName} whole")
                                failed += entry.label
                                return@extract
                            }
                            // The files are in a folder of their own, which the install takes over.
                            val dir = files.first().parentFile ?: throw IOException("No folder for the files")
                            val key = localKey(entry.packageName)
                            var attempt: Int? = null
                            val outcome = try {
                                InstallManager.installLocalAndAwait(
                                    context, key, entry.label, entry.packageName, dir, files, INSTALL_TIMEOUT_MS
                                ) { attempt = it }
                            } catch (e: CancellationException) {
                                attempt?.let { InstallManager.cancel(key, it) } ?: dir.deleteRecursively()
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not restore ${entry.packageName}", e)
                                failed += entry.label
                                if (attempt == null) dir.deleteRecursively()
                                return@extract
                            }
                            when (outcome) {
                                Outcome.INSTALLED, Outcome.CONFIRMATION_NEEDED -> saved++
                                Outcome.FAILED, Outcome.CANCELLED -> failed += entry.label
                                null -> if (attempt == null) failed += entry.label
                            }
                            if (run != null && entry.data.isNotEmpty()) {
                                if (outcome != Outcome.INSTALLED) {
                                    dataFailed += entry.label
                                } else {
                                    val wrong = restoreData(context, run, entry) { archive -> archives[archive.part]?.inputStream() }
                                    if (wrong != null) {
                                        dataFailed += entry.label
                                        if (wrong) wrongPassword = true
                                    }
                                }
                            } else if (data.include && hadData[entry.packageName] == true) {
                                dataFailed += entry.label
                            }
                        } finally {
                            dataDirs.remove(entry.packageName)?.deleteRecursively()
                        }
                    }
                )
            }
        } finally {
            dataDirs.values.forEach { it.deleteRecursively() }
        }
        return BackupState.Done(true, saved, 0, failed, zip = true, dataFailed = dataFailed, wrongPassword = wrongPassword)
    }

    private suspend fun write(
        docs: Documents,
        selected: List<BackupCandidate>,
        settingsJson: String,
        data: DataChoice
    ): BackupState {
        val root = docs.children(docs.rootId) ?: throw IOException("The folder cannot be listed")
        val rootByName = root.associateBy { it.name }.toMutableMap()
        val total = candidateBytes(selected, data)
        var done = 0L
        var saved = 0
        var skipped = 0
        val failed = ArrayList<String>()
        val dataFailed = ArrayList<String>()
        val written = HashMap<String, BackupEntry>()
        var cancelled: CancellationException? = null
        var indexWritten = false
        try {
            val run = dataRun(data)
            val parts = run?.let { RootData.parts(selected.map { it.entry.packageName }) }
            // The data was asked for but cannot be had: there is no root and no tar, or the
            // listing failed. Every app is named for it, and what the folder holds of the
            // data is kept as it is, rather than taken for the data of apps that have none.
            val dataLost = data.include && parts == null
            if (dataLost) dataFailed += selected.map { it.entry.label }
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
                    val upToDate = AppBackup.isUpToDate(stored, byName.mapValues { it.value.size }, entry)
                    val wanted = parts?.get(entry.packageName).orEmpty()
                    val oldData = byName.values.filter { AppBackup.isDataName(it.name) }
                    // Data kept from before is kept for the app it was taken from alone: the
                    // entry's signers are what a restore holds data in the clear to, and an
                    // app signed by someone else gets nothing of the old one's.
                    val sameApp = stored != null && stored.signers.toSet() == entry.signers.toSet()
                    // The files up to date, and the data either not asked for, with none
                    // left over from before, or not to be had, so that what is there stays:
                    // the folder is as it should be. The data is taken afresh whenever it can be.
                    if (upToDate && ((dataLost && sameApp) || (run == null && oldData.isEmpty()))) {
                        skipped++
                        written[entry.packageName] = stored ?: entry
                        done += entry.size
                        return@forEachIndexed
                    }
                    // The entry goes first and comes back last: a folder without one is a copy
                    // cut short, and is copied again next time. Files of an earlier version that
                    // are not among this one's go too.
                    byName.remove(AppBackup.ENTRY_NAME)?.let {
                        // While the old entry stands, the old copy is whole; it is not touched
                        // until the entry is gone, so that it never stands for files it is not.
                        if (!docs.delete(it.id)) throw IOException("The app's entry could not be removed")
                    }
                    if (upToDate) {
                        done += entry.size
                    } else {
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
                    }
                    var archives = emptyList<BackupData>()
                    if (dataLost) {
                        // What the folder holds of the data stays, and the entry goes on naming it.
                        archives = if (sameApp) stored!!.data.filter { it.name in byName } else emptyList()
                    } else if (run != null && wanted.isNotEmpty()) {
                        try {
                            archives = writeData(docs, folderId, byName, entry, run, wanted) { sofar ->
                                _state.value = BackupState.Running(false, i + 1, selected.size, entry.label, done + sofar, total)
                            }
                            done += archives.sumOf { it.size }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not take the data of ${entry.packageName}", e)
                            dataFailed += entry.label
                        }
                    }
                    // The folder holds the data as it was chosen this time: nothing of an
                    // earlier choice, which the entry would not tell of, stays. An archive
                    // that cannot be removed, as one in the clear beside an encrypted one,
                    // fails the app rather than stay unnamed beside its entry.
                    val kept = archives.mapTo(HashSet()) { it.name }
                    byName.values.filter { AppBackup.isDataName(it.name) && it.name !in kept }.forEach {
                        if (!docs.delete(it.id)) throw IOException("An old data file could not be removed: ${it.name}")
                    }
                    val made = entry.copy(data = archives)
                    docs.writeText(docs.create(folderId, JSON_MIME, AppBackup.ENTRY_NAME), made.toJson().toString(2))
                    saved++
                    written[entry.packageName] = made
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not back up ${entry.packageName}", e)
                    failed += entry.label
                }
            }
        } catch (e: CancellationException) {
            cancelled = e
        } finally {
            // What the folder holds now is written down even after a cancel, so that a restore
            // finds every app that was copied whole: the index is made of the apps' own entries,
            // which a copy cut short has none of, whether or not an index was there before.
            withContext(NonCancellable) {
                indexWritten = try {
                    val index = AppBackup.indexEntries(folderReads(docs), written)
                        ?: throw IOException("An app's folder could not be read; the index is left as it was")
                    writeRoot(docs, rootByName, AppBackup.INDEX_NAME, AppBackup.indexToJson(index, Instant.now().toString()).toString(2))
                    writeRoot(docs, rootByName, AppBackup.SETTINGS_NAME, settingsJson)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Could not write the folder's index", e)
                    false
                }
            }
        }
        cancelled?.let { throw CancelledWith(indexWritten, it) }
        return BackupState.Done(false, saved, skipped, failed, indexWritten, dataFailed = dataFailed)
    }

    /**
     * Writes the data archives of [entry], one for each of [parts], into the app's folder
     * [folderId], whose files [byName] are kept up to date; returns their descriptions. An
     * archive that cannot be written whole is removed, and the rest are not written.
     */
    private suspend fun writeData(
        docs: Documents,
        folderId: String,
        byName: MutableMap<String, Doc>,
        entry: BackupEntry,
        run: DataRun,
        parts: List<String>,
        onBytes: (Long) -> Unit
    ): List<BackupData> {
        val archives = ArrayList<BackupData>()
        var before = 0L
        for (part in parts) {
            val name = DataCrypt.fileName(part, run.encrypt)
            val id = byName[name]?.id ?: docs.create(folderId, DATA_MIME, name).also {
                byName[name] = Doc(it, name, DATA_MIME, -1)
            }
            val counting = Counting(docs.openWrite(id))
            try {
                run.wrap(counting, entry).use { sink ->
                    RootData.archive(run.tar, part, entry.packageName, sink) { sofar -> onBytes(before + sofar) }
                }
            } catch (e: Throwable) {
                runCatching { counting.close() }
                docs.delete(id)
                byName.remove(name)
                throw e
            }
            archives += BackupData(name, counting.count)
            before += counting.count
        }
        return archives
    }

    /** A cancel, carrying whether the folder's index was still written. */
    private class CancelledWith(val indexWritten: Boolean, cause: CancellationException) :
        CancellationException(cause.message) {
        init {
            initCause(cause)
        }
    }

    /**
     * What every app folder in the tree holds, by package: its entry, none (a copy cut short, or
     * an entry of another package), or not known, when the folder or its entry could not be read.
     */
    private fun folderReads(docs: Documents): Map<String, FolderRead> {
        val folders = docs.children(docs.rootId)?.filter { it.isDirectory && AppBackup.isPackageName(it.name) }
            ?: throw IOException("The folder cannot be listed")
        return folders.associate { folder ->
            val children = docs.children(folder.id)
            val entryDoc = children?.firstOrNull { it.name == AppBackup.ENTRY_NAME }
            folder.name to when {
                children == null -> FolderRead.Unreadable
                entryDoc == null -> FolderRead.NoEntry
                else -> when (val text = docs.readText(entryDoc.id, AppBackup.MAX_JSON_BYTES)) {
                    null -> FolderRead.Unreadable
                    else -> AppBackup.entryFromText(text)
                        ?.takeIf { it.packageName == folder.name }
                        ?.let { FolderRead.Found(it) }
                        ?: FolderRead.NoEntry
                }
            }
        }
    }

    private fun writeRoot(docs: Documents, rootByName: Map<String, Doc>, name: String, text: String) {
        val id = rootByName[name]?.takeIf { !it.isDirectory }?.id ?: docs.create(docs.rootId, JSON_MIME, name)
        docs.writeText(id, text)
    }

    private suspend fun install(context: Context, docs: Documents, selected: List<RestoreEntry>, data: DataChoice): BackupState {
        val total = selected.sumOf { it.entry.size + if (data.include) it.entry.dataSize else 0 }
        var done = 0L
        var saved = 0
        val failed = ArrayList<String>()
        val dataFailed = ArrayList<String>()
        var wrongPassword = false
        val run = dataRun(data)
        selected.forEachIndexed { i, row ->
            coroutineContext.ensureActive()
            val entry = row.entry
            _state.value = BackupState.Running(true, i + 1, selected.size, entry.label, done, total)
            // A folder of this app's own in the cache. It is ours until the install is asked to
            // take it: from then on it is the attempt's, which deletes it once it is settled,
            // or the install's to delete at once when no attempt begins.
            val dir = File(File(context.cacheDir, "apk"), "restore-${restores.incrementAndGet()}")
            val key = localKey(entry.packageName)
            var attempt: Int? = null
            try {
                val files = row.files ?: throw IOException("The app's folder is gone")
                if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Could not make room for the files")
                val copied = entry.files.map { file ->
                    val doc = files[file.name] ?: throw IOException("${file.name} is gone")
                    val target = File(dir, file.name)
                    val bytes = docs.openRead(doc.id).use { input ->
                        target.outputStream().use { output ->
                            copy(input, output) { sofar ->
                                _state.value = BackupState.Running(true, i + 1, selected.size, entry.label, done + sofar, total)
                            }
                        }
                    }
                    // The entry says what the file is; one of another size is another file.
                    if (bytes != file.size) throw IOException("${file.name} is $bytes bytes, not ${file.size}")
                    done += bytes
                    target
                }
                val outcome = InstallManager.installLocalAndAwait(
                    context, key, entry.label, entry.packageName, dir, copied, INSTALL_TIMEOUT_MS
                ) { attempt = it }
                when (outcome) {
                    Outcome.INSTALLED, Outcome.CONFIRMATION_NEEDED -> saved++
                    Outcome.FAILED, Outcome.CANCELLED -> failed += entry.label
                    // Nothing settled: an install of the package was already on its way, which
                    // never took these files, or the system has not answered in all this time.
                    // Either way the files are no longer ours.
                    null -> if (attempt == null) failed += entry.label
                }
                if (run != null && entry.data.isNotEmpty()) {
                    if (outcome != Outcome.INSTALLED) {
                        dataFailed += entry.label
                    } else {
                        val wrong = restoreData(context, run, entry) { archive ->
                            files[archive.name]?.let { doc ->
                                try {
                                    docs.openRead(doc.id)
                                } catch (e: IOException) {
                                    Log.w(TAG, "Could not open ${archive.name} of ${entry.packageName}", e)
                                    null
                                }
                            }
                        }
                        if (wrong != null) {
                            dataFailed += entry.label
                            if (wrong) wrongPassword = true
                        }
                        done += entry.dataSize
                    }
                } else if (data.include && entry.data.isNotEmpty()) {
                    dataFailed += entry.label
                }
            } catch (e: CancellationException) {
                // The attempt begun is given up, when it is still ours to give up; its own
                // cleanup takes the files. Files no attempt has taken are ours to remove.
                attempt?.let { InstallManager.cancel(key, it) } ?: dir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore ${entry.packageName}", e)
                failed += entry.label
                if (attempt == null) dir.deleteRecursively()
            }
        }
        return BackupState.Done(true, saved, 0, failed, dataFailed = dataFailed, wrongPassword = wrongPassword)
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
