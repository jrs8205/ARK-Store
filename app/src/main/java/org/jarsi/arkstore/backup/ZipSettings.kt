package org.jarsi.arkstore.backup

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the settings a zip file holds in the cache while the file is looked at, for an import
 * to read: one file for each read of a zip, named by the read's number.
 *
 * A read given up for another goes on until it has read what it was at, and may come to its
 * settings after the read begun later has kept its own. The numbers are given in the order the
 * reads begin, and a read removes only the files of the reads before it, so the file of the
 * read that is shown is never removed by one that is not.
 */
class ZipSettings {
    private val last = AtomicLong()

    /**
     * The number of a read that begins at [now], in milliseconds since the epoch: higher than
     * that of every read begun before it, also in an earlier run of the store, whose reads were
     * numbered by the time too.
     */
    fun begin(now: Long): Long = last.updateAndGet { maxOf(it + 1, now) }

    /**
     * Keeps [text] in [dir] as the settings of the read numbered [read] and returns its file,
     * or null when the zip had none. What the reads before it kept is removed either way, and
     * with it whatever else is there.
     */
    @Throws(IOException::class)
    fun keep(dir: File, read: Long, text: String?): File? {
        dir.listFiles()?.forEach { file ->
            val of = NAME.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
            if (of == null || of < read) file.delete()
        }
        if (text == null) return null
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No folder for the settings of a zip file")
        return File(dir, "settings-$read.json").apply { writeText(text) }
    }

    private companion object {
        val NAME = Regex("settings-(\\d+)\\.json")
    }
}
