package org.jarsi.arkstore.backup

import android.content.pm.ApplicationInfo
import org.jarsi.arkstore.data.Backup
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What an app's folder in a backup was found to hold when the folder's index is made. */
sealed interface FolderRead {
    /** The folder or its entry could not be read just now: what it holds is not known. */
    data object Unreadable : FolderRead

    /** No entry, or one that is not this folder's: a copy cut short, or none at all. */
    data object NoEntry : FolderRead

    data class Found(val entry: BackupEntry) : FolderRead
}

/** One file of a backed-up app, by the name it has in the app's folder. */
data class BackupFile(val name: String, val size: Long)

/**
 * One archive of an app's own data in a backup, taken as root: its file's name, which tells
 * the [part] of the data it holds and whether it is [encrypted], and its size, which is
 * [RestoreRules.UNKNOWN_SIZE] in a zip's head, where the size is not known when the entry is
 * written.
 */
data class BackupData(val name: String, val size: Long) {
    val encrypted: Boolean get() = DataCrypt.isEncrypted(name)

    /** [AppBackup.DATA_USER] or [AppBackup.DATA_USER_DE]. */
    val part: String get() = name.substringBefore('.')

    fun toJson(): JSONObject = JSONObject().put("name", name).put("size", size)
}

/**
 * An app as a backup folder holds it: what the device knew of it when it was copied, and the
 * files copied. Written to the app's own folder as `app.json` and gathered into the folder's
 * `index.json`; read back from either, see [AppBackup.entryFromJson].
 */
data class BackupEntry(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String?,
    /** The package that installed the app, or null when the system did not tell. */
    val installer: String?,
    /** SHA-256 digests of the certificates the app was signed with, in hex. */
    val signers: List<String>,
    /** When the app was last installed or updated on the device, in milliseconds since the epoch. */
    val updatedAt: Long,
    /** The lowest Android API level the app runs on, when known. */
    val minSdk: Int?,
    /** The files copied, `base.apk` first. */
    val files: List<BackupFile>,
    /** The archives of the app's own data, when it was taken as root; at most one per part. */
    val data: List<BackupData> = emptyList()
) {
    /** The size of the app's files; the data's size is apart, and not always known. */
    val size: Long get() = files.sumOf { it.size }

    /** The size of the data archives whose size is known. */
    val dataSize: Long get() = data.sumOf { maxOf(it.size, 0) }

    fun toJson(): JSONObject = JSONObject()
        .put("package", packageName)
        .put("label", label)
        .put("versionCode", versionCode)
        .put("versionName", versionName ?: JSONObject.NULL)
        .put("installer", installer ?: JSONObject.NULL)
        .put("signers", JSONArray(signers))
        .put("updatedAt", updatedAt)
        .put("minSdk", minSdk ?: JSONObject.NULL)
        .put("files", JSONArray(files.map { JSONObject().put("name", it.name).put("size", it.size) }))
        .also { if (data.isNotEmpty()) it.put("data", JSONArray(data.map(BackupData::toJson))) }
}

/**
 * The form of a backup folder: an app's folder named by its package, holding its APK files and
 * its entry, and at the root an index of every app. A folder may have been written by anyone,
 * so everything read from it is checked, and nothing in it is reached by a path put together
 * from what it says: a package name or a file name that would not pass is not an entry.
 */
object AppBackup {
    const val APP = "org.jarsi.arkstore"
    const val VERSION = 1

    const val INDEX_NAME = "index.json"
    const val ENTRY_NAME = "app.json"
    const val BASE_NAME = "base.apk"
    const val SETTINGS_NAME = Backup.FILE_NAME

    /** The parts of an app's data: its credential-encrypted data and its device-encrypted data. */
    const val DATA_USER = "data"
    const val DATA_USER_DE = "data_de"
    val DATA_PARTS = listOf(DATA_USER, DATA_USER_DE)

    /** The names a data archive may have in an app's folder. */
    private val DATA_NAMES = DATA_PARTS.flatMap { listOf(DataCrypt.fileName(it, false), DataCrypt.fileName(it, true)) }.toSet()

    /** The most bytes an index or an entry is read for; an index of thousands of apps is far smaller. */
    const val MAX_JSON_BYTES = 4 * 1024 * 1024
    const val MAX_ENTRIES = 5000
    const val MAX_FILES = 100
    const val MAX_SIGNERS = 10
    const val MAX_LABEL = 255
    private const val MAX_NAME = 255

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
    private val DIGEST = Regex("[0-9a-f]{64}")

    fun isPackageName(name: String): Boolean = name.length <= MAX_NAME && PACKAGE_NAME.matches(name)

    fun isFileName(name: String): Boolean = name.length <= MAX_NAME && FILE_NAME.matches(name)

    /** Whether [name] is one of the names a data archive has, see [DataCrypt.fileName]. */
    fun isDataName(name: String): Boolean = name in DATA_NAMES

    /**
     * The name a split file of the device, named [deviceName] there, gets in the backup: its own
     * when that passes and is not the base file's, otherwise one made of its [index] among the
     * splits, counted from 1.
     */
    fun fileNameFor(deviceName: String, index: Int): String =
        if (isFileName(deviceName) && deviceName != BASE_NAME) deviceName else "split_$index.apk"

    /** The entry [text] holds, or null when it is not one. */
    fun entryFromText(text: String): BackupEntry? = parse(text)?.let(::entryFromJson)

    /** The entry [json] holds, or null when anything of it is missing, of the wrong kind or out of bounds. */
    fun entryFromJson(json: JSONObject): BackupEntry? {
        val packageName = (json.opt("package") as? String)?.takeIf(::isPackageName) ?: return null
        val label = (json.opt("label") as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_LABEL } ?: return null
        val versionCode = long(json.opt("versionCode"))?.takeIf { it >= 0 } ?: return null
        val versionName = when (val value = json.opt("versionName")) {
            null, JSONObject.NULL -> null
            is String -> value.takeIf { it.length <= MAX_LABEL } ?: return null
            else -> return null
        }
        val installer = when (val value = json.opt("installer")) {
            null, JSONObject.NULL -> null
            is String -> value.takeIf(::isPackageName) ?: return null
            else -> return null
        }
        val signers = when (val value = json.opt("signers")) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> {
                if (value.length() > MAX_SIGNERS) return null
                List(value.length()) { (value.opt(it) as? String)?.takeIf(DIGEST::matches) ?: return null }
            }
            else -> return null
        }
        val updatedAt = long(json.opt("updatedAt"))?.takeIf { it >= 0 } ?: return null
        val minSdk = when (val value = json.opt("minSdk")) {
            null, JSONObject.NULL -> null
            is Int -> value.takeIf { it in 1..10_000 } ?: return null
            else -> return null
        }
        val list = json.opt("files") as? JSONArray ?: return null
        if (list.length() !in 1..MAX_FILES) return null
        val files = List(list.length()) { i ->
            val file = list.opt(i) as? JSONObject ?: return null
            val name = (file.opt("name") as? String)?.takeIf(::isFileName) ?: return null
            val size = long(file.opt("size"))?.takeIf { it >= 0 } ?: return null
            BackupFile(name, size)
        }
        if (files.first().name != BASE_NAME || files.distinctBy { it.name }.size != files.size) return null
        val data = when (val value = json.opt("data")) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> {
                if (value.length() > DATA_PARTS.size) return null
                List(value.length()) { i ->
                    val item = value.opt(i) as? JSONObject ?: return null
                    val name = (item.opt("name") as? String)?.takeIf(::isDataName) ?: return null
                    val size = long(item.opt("size"))?.takeIf { it >= RestoreRules.UNKNOWN_SIZE } ?: return null
                    BackupData(name, size)
                }
            }
            else -> return null
        }
        if (data.distinctBy { it.part }.size != data.size) return null
        return BackupEntry(packageName, label, versionCode, versionName, installer, signers, updatedAt, minSdk, files, data)
    }

    fun indexToJson(entries: List<BackupEntry>, writtenAt: String): JSONObject = JSONObject()
        .put("app", APP)
        .put("version", VERSION)
        .put("written", writtenAt)
        .put("apps", JSONArray(entries.map { it.toJson() }))

    /**
     * The entries the index [text] holds, or null when it is no index the store wrote, or one of
     * a newer store than this. An entry that does not pass is left out, and so is a later entry
     * of a package already listed.
     */
    fun indexFromText(text: String): List<BackupEntry>? {
        val json = parse(text) ?: return null
        if (json.optString("app") != APP || json.optInt("version", Int.MAX_VALUE) > VERSION) return null
        val apps = json.optJSONArray("apps") ?: return null
        val seen = HashSet<String>()
        val entries = ArrayList<BackupEntry>()
        for (i in 0 until apps.length()) {
            if (entries.size >= MAX_ENTRIES) break
            val entry = (apps.opt(i) as? JSONObject)?.let(::entryFromJson) ?: continue
            if (seen.add(entry.packageName)) entries += entry
        }
        return entries
    }

    /**
     * Whether the copy in an app's folder is the app as it is on the device now: the folder's
     * entry [stored] names the same version, installed at the same time, with the same files,
     * and the files [present] in the folder (by name, with their sizes) are all there at those
     * sizes. A file whose size the folder does not tell (-1) is copied again, to be sure of it.
     * The name and the installer may differ: they are not what was copied.
     */
    fun isUpToDate(stored: BackupEntry?, present: Map<String, Long>, current: BackupEntry): Boolean =
        stored != null &&
            stored.versionCode == current.versionCode &&
            stored.updatedAt == current.updatedAt &&
            stored.files == current.files &&
            current.files.all { present[it.name] == it.size }

    /**
     * The entries of a folder's index: those [written] in this run, by package, and those the
     * other app [folders] were found to hold, in [indexOrder]. Null when a folder not written in
     * this run could not be read: it may hold a whole copy, and an index without it would hide
     * it, so the index is better left as it was.
     */
    fun indexEntries(folders: Map<String, FolderRead>, written: Map<String, BackupEntry>): List<BackupEntry>? {
        val entries = ArrayList<BackupEntry>(folders.size)
        for ((name, read) in folders) {
            val entry = written[name] ?: when (read) {
                FolderRead.Unreadable -> return null
                FolderRead.NoEntry -> null
                is FolderRead.Found -> read.entry
            }
            if (entry != null) entries += entry
        }
        return indexOrder(entries)
    }

    /** [entries] in the order of the index and the lists: by name, then by package. */
    fun indexOrder(entries: Collection<BackupEntry>): List<BackupEntry> =
        entries.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))

    /**
     * Whether an installed app, with the [flags] of its ApplicationInfo, is one a backup may
     * take: not the system's, not an update of the system's, and not the store itself ([self]),
     * which is installed before the backup is brought back.
     */
    fun isCandidate(flags: Int, packageName: String, self: String): Boolean =
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
            packageName != self &&
            isPackageName(packageName)

    private fun parse(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (_: JSONException) {
        null
    }

    private fun long(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }
}

/**
 * The text an encrypted data archive is bound to: the package and the signers of the app
 * whose data it is, so that the data is never put back into an app signed by someone else,
 * as when the files beside it in the backup have been swapped for another app's.
 */
object DataBinding {
    private const val VERSION = "arkstore-data-binding/1"

    fun text(packageName: String, signers: Collection<String>): String =
        VERSION + "\n" + packageName + "\n" + signers.sorted().joinToString(",")

    /** The package and the signers [text] names, or null when it is not a binding of this kind. */
    fun parse(text: String): Pair<String, List<String>>? {
        val lines = text.split('\n')
        if (lines.size != 3 || lines[0] != VERSION || !AppBackup.isPackageName(lines[1])) return null
        val signers = lines[2].split(',').filter { it.isNotEmpty() }
        if (signers.any { !SIGNER.matches(it) }) return null
        return lines[1] to signers
    }

    private val SIGNER = Regex("[0-9a-f]{64}")
}

/** The packages that install apps, as a backup tells them apart. */
object Installers {
    const val PLAY = "com.android.vending"

    private val NAMES = mapOf(
        PLAY to "Google Play",
        "org.fdroid.fdroid" to "F-Droid",
        "org.fdroid.basic" to "F-Droid",
        "org.jarsi.arkstore" to "ARK-Store",
        "com.aurora.store" to "Aurora Store",
        "dev.imranr.obtainium" to "Obtainium",
        "com.looker.droidify" to "Droid-ify",
        "com.machiav3lli.fdroid" to "Neo Store",
        "com.sec.android.app.samsungapps" to "Galaxy Store"
    )

    /** Whether an app installed by [installer] is taken into a backup unless the user says otherwise: one not from Google Play. */
    fun preselected(installer: String?): Boolean = installer != PLAY

    /**
     * The name a known [installer] goes by, or null for one not known, or for the system's own
     * installer, which is the user installing a file by hand.
     */
    fun knownName(installer: String?): String? = installer?.let(NAMES::get)

    /**
     * Whether [installer] stands for the user installing a file by hand: no installer at all,
     * the system's own installer, or the shell, which is a file installed over a cable.
     */
    fun byHand(installer: String?): Boolean =
        installer == null || installer.endsWith(".packageinstaller") || installer == "com.android.shell"
}

/** How a backed-up app stands against the device it would be restored to. */
enum class RestoreStatus {
    NOT_INSTALLED,
    OLDER_INSTALLED,
    SAME_INSTALLED,
    /** The device has a newer version, which Android would not let the backup replace. */
    NEWER_INSTALLED,
    /** The installed app is signed with another key, so Android would refuse the backup's files. */
    OTHER_SIGNER,
    NEEDS_NEWER_ANDROID,
    /** The folder no longer has the files the entry names, at the sizes it names. */
    FILES_MISSING;

    /** Whether the user may choose to restore it. */
    val selectable: Boolean
        get() = this == NOT_INSTALLED || this == OLDER_INSTALLED || this == SAME_INSTALLED

    /** Whether it is chosen before the user says anything: what is missing or behind on the device. */
    val preselected: Boolean
        get() = this == NOT_INSTALLED || this == OLDER_INSTALLED
}

object RestoreRules {
    /** The size of a file whose size the folder does not tell; the file is there all the same. */
    const val UNKNOWN_SIZE = -1L

    /**
     * Whether a data archive of [packageName] may be put back into the app installed with
     * [installedSigners]: an encrypted archive [bound] to a package and signers must name this
     * package and a signer the installed app has; an archive in the clear ([bound] null) is
     * held to the entry's [entrySigners] when it names any. Nothing is put back when the
     * installed app's signers could not be read.
     */
    fun dataAllowed(
        packageName: String,
        bound: String?,
        entrySigners: List<String>,
        installedSigners: Set<String>?
    ): Boolean {
        if (installedSigners.isNullOrEmpty()) return false
        if (bound != null) {
            val (boundPackage, boundSigners) = DataBinding.parse(bound) ?: return false
            return boundPackage == packageName && boundSigners.any { it in installedSigners }
        }
        return entrySigners.isEmpty() || entrySigners.any { it in installedSigners }
    }

    /**
     * How [entry] stands against a device running Android API [sdk], where the package is
     * installed as [installedVersion] (null when it is not), signed with [keysHeld] (null when they
     * could not be read, which is not taken for a difference), and where the entry's folder holds
     * the files [present], by name and size (null when there is no folder; [UNKNOWN_SIZE] for a
     * file listed without one, which counts as there, its size checked when it is read).
     */
    fun status(
        entry: BackupEntry,
        installedVersion: Long?,
        keysHeld: Set<String>?,
        present: Map<String, Long>?,
        sdk: Int
    ): RestoreStatus = when {
        present == null || entry.files.any { present[it.name].let { size -> size == null || (size != UNKNOWN_SIZE && size != it.size) } } -> RestoreStatus.FILES_MISSING
        entry.minSdk != null && entry.minSdk > sdk -> RestoreStatus.NEEDS_NEWER_ANDROID
        installedVersion == null -> RestoreStatus.NOT_INSTALLED
        keysHeld != null && entry.signers.isNotEmpty() && keysHeld.none { it in entry.signers } -> RestoreStatus.OTHER_SIGNER
        installedVersion > entry.versionCode -> RestoreStatus.NEWER_INSTALLED
        installedVersion == entry.versionCode -> RestoreStatus.SAME_INSTALLED
        else -> RestoreStatus.OLDER_INSTALLED
    }
}

/**
 * What the user has said about a list's choices, the later word standing: the keys they
 * chose by hand and those they dropped by hand. The rest of a list follows its rule, so that
 * a list read again, with apps come and gone, keeps the user's choices and nothing else.
 */
data class Selection(val chosen: Set<String> = emptySet(), val dropped: Set<String> = emptySet()) {

    fun toggled(key: String, on: Boolean): Selection =
        if (on) Selection(chosen + key, dropped - key) else Selection(chosen - key, dropped + key)

    /** The user's word on every one of [keys] at once, which stands in for whatever was said before. */
    fun all(keys: Collection<String>, on: Boolean): Selection =
        if (on) Selection(keys.toSet(), emptySet()) else Selection(emptySet(), keys.toSet())

    companion object {
        /**
         * The keys chosen among [rows], each given with whether its rule chooses it, among those
         * [selectable]: the rule's choice unless the user [said] otherwise.
         */
        fun chosen(rows: List<Pair<String, Boolean>>, selectable: Set<String>, said: Selection): Set<String> =
            rows.filter { (key, byRule) ->
                key in selectable && ((byRule && key !in said.dropped) || key in said.chosen)
            }.mapTo(HashSet()) { it.first }
    }
}
