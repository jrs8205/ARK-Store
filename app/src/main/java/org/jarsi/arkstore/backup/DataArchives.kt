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
 *
 * What is written beside holds an app's data like any archive, in the clear when no password
 * was given, and is named by no entry. None of it is left in the folder without a word: what
 * cannot be removed is a failure.
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

        /**
         * Whether the folder is known to hold [id] no more: it could be listed, and the
         * listing does not have it. False when it is there, and when the folder cannot say.
         */
        fun gone(id: String): Boolean

        /** Whether the folder can give [id] another name. */
        fun canRename(id: String): Boolean

        /**
         * Gives [id] the name [name], which no document has, and returns its id under it. A
         * folder that renamed it otherwise than asked says so with [RenamedOtherwise], which
         * tells the id the document has now.
         */
        @Throws(IOException::class)
        fun rename(id: String, name: String): String
    }

    /**
     * What the folder holds of the app's data once [replace] is done, as its entry is to name
     * it, and why the data could not be taken, when it could not.
     */
    class Result(val archives: List<BackupData>, val failure: Exception?)

    /**
     * Removes what a run cut short left beside the archives of [folder]. It is done for every
     * app of a backup, whether or not its data is taken this time: a backup without the data
     * must not leave half of it behind either.
     */
    @Throws(IOException::class)
    fun clearLeftovers(folder: Folder) {
        for (name in AppBackup.DATA_NAMES) {
            val id = folder.find(partName(name)) ?: continue
            if (!folder.delete(id)) throw IOException("A data file left half written could not be removed: ${partName(name)}")
        }
    }

    /**
     * Takes the archives [names] into [folder], each written by [write], which closes the
     * stream it is given. When one cannot be written, none of them is kept, and the folder is
     * left with the archives it had, [old], as its entry named them; a cancel is passed on,
     * and leaves them as well. Half an archive that cannot be removed again is not a result
     * but an error thrown, so that no entry is written beside it.
     *
     * A folder that cannot rename a document, or takes none under the name an archive has
     * until it is whole, is written over the old archives where they lie, as there is no other
     * way into it. A failure then, or one while the new archives take their places, leaves
     * nothing that is the data of one moment, and nothing for the entry to name. An archive
     * the folder will not rename after saying it could is copied to its name.
     */
    @Throws(IOException::class)
    suspend fun replace(
        folder: Folder,
        names: List<String>,
        old: List<BackupData>,
        write: suspend (name: String, sink: OutputStream) -> Unit
    ): Result {
        try {
            clearLeftovers(folder)
        } catch (e: IOException) {
            // Nothing is written beside data that could not be removed; the archives the
            // folder had are as they were.
            return Result(old, e)
        }
        if (names.isEmpty()) return Result(emptyList(), null)

        val first = try {
            folder.create(partName(names.first()))
        } catch (_: IOException) {
            null
        }
        if (first == null || !folder.canRename(first)) {
            if (first != null && !folder.delete(first)) {
                throw IOException("A data file left half written could not be removed: ${partName(names.first())}")
            }
            return inPlace(folder, names, write)
        }

        class Written(val name: String, var id: String, var size: Long = 0)
        val beside = ArrayList<Written>()
        /** Removes what was written beside, and tells whether all of it went. */
        fun discard(): Boolean = beside.map { folder.delete(it.id) }.all { it }
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
            if (!discard()) throw IOException("A data file left half written could not be removed", e)
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
                        written.id = folder.rename(written.id, written.name)
                    } catch (e: IOException) {
                        // The folder will not rename after all, or renamed otherwise than asked:
                        // the archive, which is whole, is copied to its name from where it is.
                        if (e is RenamedOtherwise) written.id = e.id
                        val from = written.id
                        val named = folder.create(written.name)
                        folder.openRead(from).use { input -> folder.openWrite(named).use { input.copyTo(it) } }
                        if (!folder.delete(from)) throw IOException("A copied data file could not be removed: ${written.name}")
                        written.id = named
                    }
                    BackupData(written.name, written.size)
                },
                null
            )
        } catch (e: IOException) {
            // Nothing of it stays: neither what is still beside nor what has taken its place.
            // A removal that is refused may be of a document that is gone already, as one
            // renamed away is; only the folder's own listing says so. That it cannot be opened
            // says nothing: a folder that cannot be read just now holds what it held.
            val left = beside.filter { !folder.delete(it.id) && !folder.gone(it.id) }
            for (name in names) {
                folder.find(partName(name))?.let { folder.delete(it) }
                folder.find(name)?.let { folder.delete(it) }
            }
            // An archive that stays under its own name is the caller's to remove or to fail
            // over, as any data file its entry does not name. One that stays under another
            // name is told of by nothing, and is not passed over.
            if (left.any { written -> names.none { folder.find(it) == written.id } }) {
                throw IOException("A data file left beside the archives could not be removed", e)
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
                // An archive that cannot be written whole is removed, and the rest are not
                // written. One that stays is named a data file, which the caller removes or
                // fails over.
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
