package org.jarsi.arkstore.backup

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.jarsi.arkstore.data.Backup

/**
 * A backup as one zip file, for moving to another device or a cloud service: the same apps,
 * entries and settings as a backup folder (see [AppBackup]), written whole each time. The
 * apps' entries come first, so that a list of what the zip holds is read from its head; then
 * each app's files, app after app, so that a restore takes one app out at a time.
 *
 * A zip may have been made by anyone, by hand from a folder too: a name is taken only when it
 * is one of a backup's own, and nothing is ever written to a place named by the zip.
 */
object ZipBackup {
    const val MIME_TYPE = "application/zip"

    private const val BUFFER_BYTES = 64 * 1024
    private const val PROGRESS_STEP_BYTES = 1024 * 1024L

    fun fileName(date: LocalDate): String = "ark-store-backup-$date.zip"

    /** What a name in the zip stands for. */
    sealed interface Name {
        data object Index : Name
        data object Settings : Name
        data class Entry(val packageName: String) : Name
        data class File(val packageName: String, val fileName: String) : Name
        /** An archive of the app's own data, see [BackupData]. */
        data class Data(val packageName: String, val fileName: String) : Name
    }

    /**
     * What [name] stands for, or null when it is none of a backup's own. A folder zipped by hand
     * puts the folder's name in front of everything; one such part is passed over.
     */
    fun parseName(name: String): Name? {
        if (name.isEmpty() || '\\' in name || name.startsWith('/')) return null
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return when (parts.size) {
            1 -> rootName(parts[0])
            2 -> appName(parts[0], parts[1]) ?: rootName(parts[1])
            3 -> appName(parts[1], parts[2])
            else -> null
        }
    }

    private fun rootName(name: String): Name? = when (name) {
        AppBackup.INDEX_NAME -> Name.Index
        AppBackup.SETTINGS_NAME -> Name.Settings
        else -> null
    }

    private fun appName(pkg: String, file: String): Name? = when {
        !AppBackup.isPackageName(pkg) -> null
        file == AppBackup.ENTRY_NAME -> Name.Entry(pkg)
        AppBackup.isFileName(file) -> Name.File(pkg, file)
        AppBackup.isDataName(file) -> Name.Data(pkg, file)
        else -> null
    }

    /**
     * Writes a zip of [entries] to [out]: the index, the store's settings as [settingsJson], each
     * app's entry, and then each app's files in turn, read through [open] (the entry and the
     * index of its file). A file of another size than its entry says fails the whole zip, which
     * then would hold an entry for files it has not. After an app's files come its data
     * archives, each written by [writeData] into the stream it is given, whose size the entry
     * does not know. [onBytes] is told the bytes of files written so far. [out] is left open.
     */
    suspend fun write(
        out: OutputStream,
        entries: List<BackupEntry>,
        settingsJson: String,
        writtenAt: String,
        open: (BackupEntry, Int) -> InputStream,
        onBytes: (Long) -> Unit = {},
        writeData: suspend (BackupEntry, BackupData, OutputStream) -> Unit = { _, _, _ -> }
    ) {
        val zip = ZipOutputStream(out)
        fun text(name: String, text: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        text(AppBackup.INDEX_NAME, AppBackup.indexToJson(AppBackup.indexOrder(entries), writtenAt).toString(2))
        text(AppBackup.SETTINGS_NAME, settingsJson)
        for (entry in entries) text("${entry.packageName}/${AppBackup.ENTRY_NAME}", entry.toJson().toString(2))
        // An app's files are packed already; packing them again would only take time.
        zip.setLevel(Deflater.NO_COMPRESSION)
        var done = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        for (entry in entries) {
            entry.files.forEachIndexed { i, file ->
                zip.putNextEntry(ZipEntry("${entry.packageName}/${file.name}"))
                var copied = 0L
                var told = 0L
                open(entry, i).use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                        copied += n
                        if (copied - told >= PROGRESS_STEP_BYTES) {
                            told = copied
                            onBytes(done + copied)
                        }
                    }
                }
                if (copied != file.size) {
                    throw IOException("${entry.packageName}/${file.name} is $copied bytes, not ${file.size}")
                }
                zip.closeEntry()
                done += copied
                onBytes(done)
            }
            for (data in entry.data) {
                zip.putNextEntry(ZipEntry("${entry.packageName}/${data.name}"))
                writeData(entry, data, Unclosable(zip))
                zip.closeEntry()
            }
        }
        zip.finish()
        zip.flush()
    }

    /** The zip's stream as given to a writer of one item: closing it closes the item alone. */
    private class Unclosable(private val zip: ZipOutputStream) : OutputStream() {
        override fun write(b: Int) = zip.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = zip.write(b, off, len)
        override fun flush() = zip.flush()
        override fun close() = Unit
    }

    /** What the head of a zip tells: its apps' entries, and the store's settings when it has them. */
    data class Head(val entries: List<BackupEntry>, val settings: String?)

    /**
     * Reads the apps' entries and the settings from [input]. A zip the store wrote has its index,
     * its settings and every app's entry before the first file, where the reading stops once the
     * index's apps are all found; any other zip, as a folder zipped by hand, is read to its end.
     * An entry that does not pass, or that names another package than its folder, is left out. A
     * stream that is no zip holds nothing.
     */
    suspend fun readHead(input: InputStream): Head {
        val entries = LinkedHashMap<String, BackupEntry>()
        var settings: String? = null
        var indexed: Set<String>? = null
        val zip = ZipInputStream(input)
        while (true) {
            coroutineContext.ensureActive()
            val item = zip.nextEntry ?: break
            if (item.isDirectory) continue
            when (val name = parseName(item.name)) {
                Name.Settings -> if (settings == null) settings = readText(zip, Backup.MAX_BYTES)
                is Name.Entry -> {
                    val entry = readText(zip, AppBackup.MAX_JSON_BYTES)
                        ?.let(AppBackup::entryFromText)
                        ?.takeIf { it.packageName == name.packageName }
                    if (entry != null && entry.packageName !in entries && entries.size < AppBackup.MAX_ENTRIES) {
                        entries[entry.packageName] = entry
                    }
                }
                is Name.File, is Name.Data -> {
                    val all = indexed
                    if (all != null && settings != null && entries.keys.containsAll(all)) break
                }
                Name.Index -> if (indexed == null) {
                    indexed = readText(zip, AppBackup.MAX_JSON_BYTES)
                        ?.let(AppBackup::indexFromText)
                        ?.mapTo(HashSet()) { it.packageName }
                }
                null -> Unit
            }
        }
        return Head(entries.values.toList(), settings)
    }

    /**
     * Takes the files of the apps [wanted] out of [input], one app at a time, each into the
     * directory [dirFor] gives it, and hands each to [onApp] once its files are all there at the
     * sizes its entry names, or null when they are not: missing, larger than said, or not
     * together. With them come the app's data archives found whole, by their part ([BackupData.part]);
     * one missing or of another size than said is left out, and the app's files stand without it.
     * With the files the directory is [onApp]'s; without them it is removed here. [onBytes] is
     * told the bytes taken out so far.
     */
    suspend fun extract(
        input: InputStream,
        wanted: List<BackupEntry>,
        dirFor: (BackupEntry) -> File,
        onApp: suspend (BackupEntry, List<File>?, Map<String, File>) -> Unit,
        onBytes: (Long) -> Unit = {}
    ) {
        val byPackage = wanted.associateBy { it.packageName }
        val done = HashSet<String>()
        var current: Taking? = null
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)

        suspend fun finish(taking: Taking) {
            done += taking.entry.packageName
            current = null
            val files = taking.entry.files.map { taking.files[it.name] }
            val whole = !taking.broken && files.zip(taking.entry.files).all { (f, said) -> f != null && f.length() == said.size }
            if (whole) {
                val data = taking.entry.data.mapNotNull { said ->
                    val file = taking.data[said.name] ?: return@mapNotNull null
                    if (said.size != RestoreRules.UNKNOWN_SIZE && file.length() != said.size) return@mapNotNull null
                    said.part to file
                }.toMap()
                onApp(taking.entry, files.map { it!! }, data)
            } else {
                taking.dir.deleteRecursively()
                onApp(taking.entry, null, emptyMap())
            }
        }

        try {
            val zip = ZipInputStream(input)
            while (true) {
                coroutineContext.ensureActive()
                val item = zip.nextEntry ?: break
                if (item.isDirectory) continue
                val (packageName, fileName, isData) = when (val name = parseName(item.name)) {
                    is Name.File -> Triple(name.packageName, name.fileName, false)
                    is Name.Data -> Triple(name.packageName, name.fileName, true)
                    else -> continue
                }
                val entry = byPackage[packageName] ?: continue
                if (packageName in done) continue
                // What the entry does not name is not the app's, whatever the zip calls it.
                val saidSize = if (isData) {
                    entry.data.firstOrNull { it.name == fileName }?.size ?: continue
                } else {
                    entry.files.firstOrNull { it.name == fileName }?.size ?: continue
                }
                current?.let { if (it.entry.packageName != packageName) finish(it) }
                val taking = current ?: Taking(entry, dirFor(entry).also { it.mkdirs() }).also { current = it }
                if (isData) {
                    // The archive is the app's extra; a second of the same name is left alone.
                    if (fileName in taking.data) continue
                } else if (fileName in taking.files || taking.broken) {
                    taking.broken = true
                    continue
                }
                val target = File(taking.dir, fileName)
                var copied = 0L
                var told = 0L
                var overflow = false
                target.outputStream().use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = zip.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (saidSize != RestoreRules.UNKNOWN_SIZE && copied > saidSize) {
                            overflow = true
                            break
                        }
                        output.write(buffer, 0, n)
                        if (copied - told >= PROGRESS_STEP_BYTES) {
                            told = copied
                            onBytes(total + copied)
                        }
                    }
                }
                total += copied
                onBytes(total)
                if (overflow) {
                    if (isData) target.delete() else taking.broken = true
                    continue
                }
                if (isData) taking.data[fileName] = target else taking.files[fileName] = target
            }
            current?.let { finish(it) }
        } catch (e: Throwable) {
            current?.dir?.deleteRecursively()
            if (e is IOException) {
                // What could not be read is missing for the apps not done yet.
                for (entry in wanted) if (entry.packageName !in done) {
                    done += entry.packageName
                    onApp(entry, null, emptyMap())
                }
                return
            }
            throw e
        }
        for (entry in wanted) if (entry.packageName !in done) onApp(entry, null, emptyMap())
    }

    private class Taking(val entry: BackupEntry, val dir: File) {
        val files = HashMap<String, File>()
        val data = HashMap<String, File>()
        var broken = false
    }

    /** The text of the zip's current entry, or null when it is longer than [maxBytes]. */
    private fun readText(zip: ZipInputStream, maxBytes: Int): String? {
        val bytes = ByteArray(maxBytes + 1)
        var count = 0
        while (count < bytes.size) {
            val n = zip.read(bytes, count, bytes.size - count)
            if (n < 0) break
            count += n
        }
        return if (count > maxBytes) null else String(bytes, 0, count, Charsets.UTF_8)
    }
}
