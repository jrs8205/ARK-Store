package org.jarsi.arkstore.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

/**
 * Puts the data archives of one app into its folder of a backup so that data which cannot be
 * taken costs the folder nothing: each new archive is written beside the old one, under a name
 * of its own, and takes the old one's place only once every archive of the app is whole. Taking
 * the data fails for reasons that have nothing to do with the folder, such as root given for a
 * while only, and the archives of the last backup are then the only copy there is.
 */
internal object DataArchives {
    private const val PART = ".part"

    /** The name the archive [name] is written under until it is whole. */
    fun partName(name: String): String = name + PART

    /** One app's folder in a backup, as far as its data archives go. */
    interface Folder {
        /** The id of the document named [name], or null when the folder has none. */
        fun find(name: String): String?

        /** Makes an empty document named [name] and returns its id. */
        @Throws(IOException::class)
        fun create(name: String): String

        /** Opens [id] for writing from the start, dropping what it held. */
        @Throws(IOException::class)
        fun openWrite(id: String): OutputStream

        @Throws(IOException::class)
        fun openRead(id: String): InputStream

        fun delete(id: String): Boolean

        /** Whether the folder can give [id] another name. */
        fun canRename(id: String): Boolean

        /** Gives [id] the name [name], which no document has, and returns its id under it. */
        @Throws(IOException::class)
        fun rename(id: String, name: String): String
    }

    /**
     * What the folder holds of the app's data once [replace] is done, as its entry is to name
     * it, and why the data could not be taken, when it could not.
     */
    class Result(val archives: List<BackupData>, val failure: Exception?)

    /**
     * Takes the archives [names] into [folder], each written by [write], which closes the
     * stream it is given. When one cannot be written, none of them is kept, and the folder is
     * left with the archives it had, [old], as its entry named them; a cancel is passed on,
     * and leaves them as well.
     *
     * A folder that cannot rename a document, or takes none under the name an archive has
     * until it is whole, is written over the old archives where they lie, as there is no other
     * way into it. A failure then, or one while the new archives take their places, leaves
     * nothing that is the data of one moment, and nothing for the entry to name. An archive
     * the folder will not rename after saying it could is copied to its name.
     */
    suspend fun replace(
        folder: Folder,
        names: List<String>,
        old: List<BackupData>,
        write: suspend (name: String, sink: OutputStream) -> Unit
    ): Result {
        // What a run cut short left beside the archives.
        for (name in AppBackup.DATA_NAMES) folder.find(partName(name))?.let { folder.delete(it) }
        if (names.isEmpty()) return Result(emptyList(), null)

        val first = try {
            folder.create(partName(names.first()))
        } catch (_: IOException) {
            null
        }
        if (first == null || !folder.canRename(first)) {
            first?.let { folder.delete(it) }
            return inPlace(folder, names, write)
        }

        class Written(val name: String, val id: String, var size: Long = 0)
        val beside = ArrayList<Written>()
        fun discard() = beside.forEach { folder.delete(it.id) }
        val failure: Exception? = try {
            for (name in names) {
                val written = Written(name, if (beside.isEmpty()) first else folder.create(partName(name)))
                beside += written
                written.size = writeWhole(folder, written.id, name, write)
            }
            null
        } catch (e: CancellationException) {
            discard()
            throw e
        } catch (e: Exception) {
            discard()
            e
        }
        if (failure != null) return Result(old, failure)

        // Every archive is whole: each takes the place of the old one of its name.
        return try {
            Result(
                beside.map { written ->
                    folder.find(written.name)?.let {
                        if (!folder.delete(it)) throw IOException("An old data file could not be removed: ${written.name}")
                    }
                    try {
                        folder.rename(written.id, written.name)
                    } catch (e: IOException) {
                        // The folder will not rename after all: the archive, which is whole, is
                        // copied to its name instead.
                        val named = folder.create(written.name)
                        folder.openRead(written.id).use { input -> folder.openWrite(named).use { input.copyTo(it) } }
                        folder.delete(written.id)
                    }
                    BackupData(written.name, written.size)
                },
                null
            )
        } catch (e: IOException) {
            for (name in names) {
                folder.find(partName(name))?.let { folder.delete(it) }
                folder.find(name)?.let { folder.delete(it) }
            }
            Result(emptyList(), e)
        }
    }

    /** Writes the archives over the old ones of their names; see [replace]. */
    private suspend fun inPlace(
        folder: Folder,
        names: List<String>,
        write: suspend (name: String, sink: OutputStream) -> Unit
    ): Result {
        val archives = ArrayList<BackupData>()
        for (name in names) {
            val id = folder.find(name) ?: try {
                folder.create(name)
            } catch (e: IOException) {
                return Result(emptyList(), e)
            }
            try {
                archives += BackupData(name, writeWhole(folder, id, name, write))
            } catch (e: CancellationException) {
                folder.delete(id)
                throw e
            } catch (e: Exception) {
                // An archive that cannot be written whole is removed, and the rest are not written.
                folder.delete(id)
                return Result(emptyList(), e)
            }
        }
        return Result(archives, null)
    }

    /** Has [write] fill the document [id] with the archive [name], and returns how much it wrote. */
    private suspend fun writeWhole(
        folder: Folder,
        id: String,
        name: String,
        write: suspend (name: String, sink: OutputStream) -> Unit
    ): Long {
        val counting = Counting(folder.openWrite(id))
        try {
            write(name, counting)
        } catch (e: Throwable) {
            runCatching { counting.close() }
            throw e
        }
        return counting.count
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
}
