package org.jarsi.arkstore.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DataArchivesTest {

    private val user = "data.tar.gz"
    private val device = "data_de.tar.gz"

    /** A folder held in memory: its documents by id, each with a name and what was written to it. */
    private class Folder(
        private val renames: Boolean = true,
        /** Whether a document can be made under the name an archive has until it is whole. */
        private val takesParts: Boolean = true
    ) : DataArchives.Folder {
        private class File(var name: String, var bytes: ByteArray)

        private val files = LinkedHashMap<String, File>()
        private var next = 1

        /** What the next rename does instead of renaming, once. */
        var onRename: (() -> Unit)? = null

        /** Whether the folder has stopped taking anything new. */
        var full = false

        /** The names of the documents the folder will not remove. */
        var kept = emptySet<String>()

        /** Whether a rename is carried out under another name, and another id, than asked. */
        var renamesOtherwise = false

        /** The names of the documents the folder will not let be read just now. */
        var unreadable = emptySet<String>()

        /** Whether the folder can be listed just now. */
        var listed = true

        /** Whether the folder answers with what it has so far and says it is still loading. */
        var loading = false

        /** The names of the documents a listing does not show although they are there. */
        var unlisted = emptySet<String>()

        fun put(name: String, text: String) {
            files["id${next++}"] = File(name, text.toByteArray())
        }

        /** What the folder holds, by name. */
        fun contents(): Map<String, String> = files.values.associate { it.name to String(it.bytes) }

        override fun find(name: String): String? = files.entries.firstOrNull { it.value.name == name }?.key

        override fun create(name: String): String {
            if (full) throw IOException("The folder is full")
            if (find(name) != null) throw IOException("$name is there already")
            if (!takesParts && name == DataArchives.partName(name.removeSuffix(".part"))) {
                throw IOException("The folder named the new document otherwise")
            }
            return "id${next++}".also { files[it] = File(name, ByteArray(0)) }
        }

        override fun openWrite(id: String): OutputStream {
            val file = files[id] ?: throw IOException("No such document")
            // Opened for writing from the start: what it held is gone at once.
            file.bytes = ByteArray(0)
            return object : ByteArrayOutputStream() {
                override fun close() {
                    file.bytes = toByteArray()
                }
            }
        }

        override fun openRead(id: String): InputStream {
            val file = files[id] ?: throw IOException("No such document")
            if (file.name in unreadable) throw IOException("The folder cannot read this document now")
            return ByteArrayInputStream(file.bytes)
        }

        override fun ids(): Set<String>? =
            if (!listed || loading) null else files.filterValues { it.name !in unlisted }.keys.toSet()

        override fun delete(id: String): Boolean {
            if (files[id]?.name in kept) return false
            return files.remove(id) != null
        }

        override fun canRename(id: String): Boolean = renames

        override fun rename(id: String, name: String): String {
            onRename?.let {
                onRename = null
                it()
            }
            if (find(name) != null) throw IOException("$name is there already")
            val file = files[id] ?: throw IOException("No such document")
            if (renamesOtherwise) {
                // Renamed, but not to the name asked for, and known by another id since.
                files.remove(id)
                val moved = "id${next++}"
                files[moved] = file.also { it.name = "$name.gz" }
                throw RenamedOtherwise(moved, "The folder named the document $name.gz instead of $name")
            }
            file.name = name
            return id
        }
    }

    private fun writing(vararg texts: Pair<String, String>): suspend (String, OutputStream) -> Unit {
        val byName = texts.toMap()
        return { name, sink -> sink.use { it.write(byName.getValue(name).toByteArray()) } }
    }

    @Test
    fun newArchivesTakeTheOldOnesPlace() = runBlocking {
        val folder = Folder().apply {
            put(user, "old user")
            put("base.apk", "apk")
        }
        val result = DataArchives.replace(folder, listOf(user, device), emptyList(), writing(user to "new user", device to "new device"))
        assertNull(result.failure)
        assertEquals(listOf(BackupData(user, 8), BackupData(device, 10)), result.archives)
        assertEquals(mapOf(user to "new user", "base.apk" to "apk", device to "new device"), folder.contents())
    }

    @Test
    fun dataThatCannotBeTakenLeavesTheOldArchivesAsTheyWere() = runBlocking {
        val folder = Folder().apply {
            put(user, "old user")
            put(device, "old device")
        }
        val old = listOf(BackupData(user, 8), BackupData(device, 10))
        val failure = IOException("tar gave up")
        // The first part is taken whole, the second fails halfway.
        val result = DataArchives.replace(folder, listOf(user, device), old) { name, sink ->
            sink.use {
                it.write("new".toByteArray())
                if (name == device) throw failure
            }
        }
        assertSame(failure, result.failure)
        assertEquals(old, result.archives)
        // Neither old archive is touched, the one whose new copy was whole included: the two
        // parts are of one moment, and nothing is left beside them.
        assertEquals(mapOf(user to "old user", device to "old device"), folder.contents())
    }

    @Test
    fun whatARunCutShortLeftBesideTheArchivesIsRemoved() = runBlocking {
        val folder = Folder().apply {
            put(user, "old user")
            put(DataArchives.partName(user), "half")
            put(DataArchives.partName("$device.aes"), "half")
        }
        DataArchives.replace(folder, listOf(user), emptyList(), writing(user to "new user"))
        assertEquals(mapOf(user to "new user"), folder.contents())
    }

    @Test
    fun cancelIsPassedOnAndLeavesTheOldArchives() {
        val folder = Folder().apply { put(user, "old user") }
        try {
            runBlocking {
                DataArchives.replace(folder, listOf(user), emptyList()) { _, sink ->
                    sink.use { it.write("ne".toByteArray()) }
                    throw CancellationException("cancelled")
                }
            }
            fail("The cancel was swallowed")
        } catch (_: CancellationException) {
        }
        assertEquals(mapOf(user to "old user"), folder.contents())
    }

    @Test
    fun folderThatCannotRenameIsWrittenInPlace() = runBlocking {
        val folder = Folder(renames = false).apply { put(user, "old user") }
        val result = DataArchives.replace(folder, listOf(user, device), emptyList(), writing(user to "new user", device to "new device"))
        assertNull(result.failure)
        assertEquals(listOf(BackupData(user, 8), BackupData(device, 10)), result.archives)
        assertEquals(mapOf(user to "new user", device to "new device"), folder.contents())
    }

    @Test
    fun folderThatTakesNoDocumentBesideTheOldIsWrittenInPlace() = runBlocking {
        val folder = Folder(takesParts = false).apply { put(user, "old user") }
        val result = DataArchives.replace(folder, listOf(user), emptyList(), writing(user to "new user"))
        assertNull(result.failure)
        assertEquals(listOf(BackupData(user, 8)), result.archives)
        assertEquals(mapOf(user to "new user"), folder.contents())
    }

    @Test
    fun failureInAFolderThatCannotRenameKeepsNothingToName() = runBlocking {
        val folder = Folder(renames = false).apply {
            put(user, "old user")
            put(device, "old device")
        }
        val old = listOf(BackupData(user, 8), BackupData(device, 10))
        val result = DataArchives.replace(folder, listOf(user, device), old) { name, sink ->
            sink.use { if (name == device) throw IOException("tar gave up") else it.write("new user".toByteArray()) }
        }
        assertTrue(result.failure is IOException)
        // Written over where they lay: what is left is not what the entry named before.
        assertEquals(emptyList<BackupData>(), result.archives)
        assertEquals(mapOf(user to "new user"), folder.contents())
    }

    @Test
    fun archiveTheFolderWillNotRenameAfterAllIsCopiedToItsName() = runBlocking {
        val folder = Folder().apply { put(user, "old user") }
        folder.onRename = { throw IOException("The folder named it otherwise") }
        val result = DataArchives.replace(folder, listOf(user, device), emptyList(), writing(user to "new user", device to "new device"))
        assertNull(result.failure)
        assertEquals(listOf(BackupData(user, 8), BackupData(device, 10)), result.archives)
        assertEquals(mapOf(user to "new user", device to "new device"), folder.contents())
    }

    @Test
    fun archiveRenamedOtherwiseThanAskedIsCopiedFromWhereItIsNow() = runBlocking {
        val folder = Folder().apply { put(user, "old user") }
        folder.renamesOtherwise = true
        val result = DataArchives.replace(folder, listOf(user), emptyList(), writing(user to "new user"))
        assertNull(result.failure)
        assertEquals(listOf(BackupData(user, 8)), result.archives)
        // Nothing is left under the name the folder gave it.
        assertEquals(mapOf(user to "new user"), folder.contents())
    }

    @Test
    fun archiveLeftUnderAnotherNameThatCanBeNeitherReadNorRemovedIsNotPassedOver() {
        // Renamed otherwise than asked, and then neither readable, to copy it to its name,
        // nor removable. That it cannot be read does not say it is gone: it is an app's data
        // under a name nothing knows, and no entry is to be written beside it.
        val folder = Folder().apply { put(user, "old user") }
        folder.renamesOtherwise = true
        folder.unreadable = setOf("$user.gz")
        folder.kept = setOf("$user.gz")
        try {
            runBlocking { DataArchives.replace(folder, listOf(user), listOf(BackupData(user, 8)), writing(user to "new user")) }
            fail("An archive left under a name nothing knows was passed over")
        } catch (_: IOException) {
        }
        assertEquals(mapOf("$user.gz" to "new user"), folder.contents())
    }

    @Test
    fun listingStillOnItsWayDoesNotSayAnArchiveIsGone() {
        // A folder in the cloud may answer with what it has at hand and say that the rest is
        // loading. What such a listing lacks is not known to be gone: here the archive is
        // there, readable, and only its removal is refused.
        val folder = Folder().apply { put(user, "old user") }
        folder.renamesOtherwise = true
        folder.kept = setOf("$user.gz")
        folder.full = false
        // The copy to its name fails, so that the archive under the other name is what is left.
        folder.onRename = { folder.full = true }
        folder.loading = true
        try {
            runBlocking { DataArchives.replace(folder, listOf(user), emptyList(), writing(user to "new user")) }
            fail("An archive a listing still loading did not show was taken for gone")
        } catch (_: IOException) {
        }
        assertEquals(mapOf("$user.gz" to "new user"), folder.contents())
    }

    @Test
    fun archiveThatStillOpensIsThereWhateverTheListingSays() {
        val folder = Folder().apply { put(user, "old user") }
        folder.renamesOtherwise = true
        folder.kept = setOf("$user.gz")
        folder.onRename = { folder.full = true }
        folder.unlisted = setOf("$user.gz")
        try {
            runBlocking { DataArchives.replace(folder, listOf(user), emptyList(), writing(user to "new user")) }
            fail("An archive that can still be opened was taken for gone")
        } catch (_: IOException) {
        }
    }

    @Test
    fun removalThatCannotBeConfirmedIsNotTakenForOne() {
        // The removal is refused and the folder cannot be listed either: nothing says the
        // archive is gone.
        val folder = Folder().apply { put(user, "old user") }
        folder.renamesOtherwise = true
        folder.unreadable = setOf("$user.gz")
        folder.kept = setOf("$user.gz")
        folder.listed = false
        try {
            runBlocking { DataArchives.replace(folder, listOf(user), emptyList(), writing(user to "new user")) }
            fail("A removal nothing confirmed was taken for one")
        } catch (_: IOException) {
        }
    }

    @Test
    fun leftoverOfARunCutShortThatCannotBeRemovedIsAFailure() = runBlocking {
        // Half an archive in the clear from a run that ended in the middle; this run encrypts.
        val folder = Folder().apply { put(DataArchives.partName(user), "half, in the clear") }
        folder.kept = setOf(DataArchives.partName(user))
        val result = DataArchives.replace(folder, listOf("$user.aes"), emptyList(), writing("$user.aes" to "encrypted"))
        assertTrue(result.failure is IOException)
        assertEquals(emptyList<BackupData>(), result.archives)
        // No archive is written beside data that could not be removed.
        assertEquals(mapOf(DataArchives.partName(user) to "half, in the clear"), folder.contents())
    }

    @Test
    fun leftoversAreClearedWhetherOrNotDataIsTaken() {
        val folder = Folder().apply {
            put(user, "old user")
            put(DataArchives.partName(user), "half")
            put(DataArchives.partName("$device.aes"), "half")
            put("base.apk", "apk")
        }
        DataArchives.clearLeftovers(folder)
        assertEquals(mapOf(user to "old user", "base.apk" to "apk"), folder.contents())

        folder.put(DataArchives.partName(device), "half")
        folder.kept = setOf(DataArchives.partName(device))
        try {
            DataArchives.clearLeftovers(folder)
            fail("A leftover that stays was passed over")
        } catch (_: IOException) {
        }
    }

    @Test
    fun halfAnArchiveThatCannotBeRemovedAfterAFailureIsNotPassedOver() {
        val folder = Folder().apply { put(user, "old user") }
        folder.kept = setOf(DataArchives.partName(user))
        try {
            runBlocking {
                DataArchives.replace(folder, listOf(user), listOf(BackupData(user, 8))) { _, sink ->
                    sink.use { it.write("ne".toByteArray()) }
                    throw IOException("tar gave up")
                }
            }
            fail("Half an archive was left beside the entry without a word")
        } catch (e: IOException) {
            assertEquals("tar gave up", e.cause?.message)
        }
    }

    @Test
    fun archiveThatCannotBePutUnderItsNameIsAFailureWithNothingLeftBeside() = runBlocking {
        val folder = Folder().apply { put(user, "old user") }
        folder.onRename = {
            folder.full = true
            throw IOException("The folder named it otherwise")
        }
        val result = DataArchives.replace(folder, listOf(user), listOf(BackupData(user, 8)), writing(user to "new user"))
        assertTrue(result.failure is IOException)
        assertEquals(emptyList<BackupData>(), result.archives)
        assertEquals(emptyMap<String, String>(), folder.contents())
    }
}
