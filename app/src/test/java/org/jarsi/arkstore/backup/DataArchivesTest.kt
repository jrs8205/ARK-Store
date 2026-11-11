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

        override fun openRead(id: String): InputStream =
            ByteArrayInputStream((files[id] ?: throw IOException("No such document")).bytes)

        override fun delete(id: String): Boolean = files.remove(id) != null

        override fun canRename(id: String): Boolean = renames

        override fun rename(id: String, name: String): String {
            onRename?.let {
                onRename = null
                it()
            }
            if (find(name) != null) throw IOException("$name is there already")
            (files[id] ?: throw IOException("No such document")).name = name
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
