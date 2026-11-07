package org.jarsi.arkstore.install

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.jarsi.arkstore.FakePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What becomes of a checked file: installed through root when root is on and given, saved to
 * the folder for the user when the user asked for it, and nothing at all when the background
 * check asked and root could not be had.
 */
class InstallManagerTest {

    /** A context of preferences alone, which is all that the hand-over asks of it. */
    private class PrefsContext : ContextWrapper(null) {
        val prefs = HashMap<String, FakePreferences>()
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "org.jarsi.arkstore"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            prefs.getOrPut(name!!) { FakePreferences() }
    }

    private val context = PrefsContext()
    private val saves = ArrayList<String>()
    private val deleted = ArrayList<String>()

    private fun aFile(): File = File.createTempFile("attempt", ".apk").apply {
        writeBytes(byteArrayOf(1, 2, 3))
        deleteOnExit()
    }

    /** Saves under the given names as [uris], in turn, noting the names asked for. */
    private fun saving(vararg uris: String) {
        val next = uris.iterator()
        InstallManager.saver = { _, _, name ->
            saves += name
            next.next()
        }
        InstallManager.deleter = { _, uri -> deleted += uri }
    }

    private fun saveAs(versionCode: Long = 5) =
        InstallManager.SaveAs("Example-1.$versionCode.apk", versionCode, "1.$versionCode", "owner/example")

    @After
    fun after() {
        InstallManager.setRootLost(context, false)
        Root.transport = null
        InstallManager.saver = null
        InstallManager.deleter = null
    }

    @Test
    fun backgroundWithoutRootFailsWithNoRootAndSavesNothing() = runBlocking {
        Root.transport = { _, _, _, _ -> Root.Outcome.NoRoot }
        saving("content://downloads/1")
        val result = InstallManager.handOver(
            context, "owner/background", null, "org.example.background", listOf("base.apk" to aFile()),
            rootWanted = true, saveAs = null
        )
        assertEquals(InstallManager.HandOver.Failed(InstallState.Failed(FailReason.NO_ROOT)), result)
        assertEquals(emptyList<String>(), saves)
        assertTrue("org.example.background" !in InstallManager.saved.value)
    }

    @Test
    fun foregroundWithoutRootSavesAndRecordsTheFile() = runBlocking {
        saving("content://downloads/1")
        val result = InstallManager.handOver(
            context, "owner/example", null, "org.example", listOf("base.apk" to aFile()),
            rootWanted = false, saveAs = saveAs()
        )
        val record = SavedApk("org.example", 5, "1.5", "content://downloads/1", "owner/example")
        assertEquals(InstallManager.HandOver.Saved(record), result)
        assertEquals(listOf("Example-1.5.apk"), saves)
        assertEquals(record, InstallManager.saved.value["org.example"])
        // Kept for the next start of the app.
        assertEquals(record, SavedApks.parse(context.prefs.getValue("saved_apks").getString("records", null))["org.example"])
    }

    @Test
    fun rootRefusedInTheForegroundSavesTheFileInstead() = runBlocking {
        Root.transport = { _, _, _, _ -> Root.Outcome.Ran(1, "Permission denied", "") }
        saving("content://downloads/1")
        val result = InstallManager.handOver(
            context, "owner/example", null, "org.example", listOf("base.apk" to aFile()),
            rootWanted = true, saveAs = saveAs()
        )
        assertTrue(result is InstallManager.HandOver.Saved)
    }

    @Test
    fun savingANewerVersionDeletesTheOlderRecordedFileOnly() = runBlocking {
        saving("content://downloads/11", "content://downloads/12")
        InstallManager.handOver(context, "owner/newer", null, "org.example.newer", listOf("base.apk" to aFile()), false, saveAs(5))
        assertEquals(emptyList<String>(), deleted)
        InstallManager.handOver(context, "owner/newer", null, "org.example.newer", listOf("base.apk" to aFile()), false, saveAs(6))
        assertEquals(listOf("content://downloads/11"), deleted)
        assertEquals("content://downloads/12", InstallManager.saved.value.getValue("org.example.newer").uri)
    }

    @Test
    fun rootGivenAgainTellsTheRowsAsWellAsTheSettings() = runBlocking {
        Root.setEnabled(context, true)
        Root.transport = { _, _, _, _ -> Root.Outcome.NoRoot }
        saving("content://downloads/31")
        InstallManager.handOver(context, "owner/lost", null, "org.example.lost", listOf("base.apk" to aFile()), true, saveAs())
        assertTrue(InstallManager.rootLost.value)
        assertTrue(Root.lost(context))
        InstallManager.setRootLost(context, false)
        assertFalse(InstallManager.rootLost.value)
        assertFalse(Root.lost(context))
    }

    @Test
    fun anOlderDownloadThatFinishesLastKeepsTheNewerFile() = runBlocking {
        // Two rows of the same app, from two projects: the newer one's download finished first.
        saving("content://downloads/21", "content://downloads/22")
        InstallManager.handOver(context, "owner/fast", null, "org.example.race", listOf("base.apk" to aFile()), false, saveAs(6))
        val result = InstallManager.handOver(context, "owner/slow", null, "org.example.race", listOf("base.apk" to aFile()), false, saveAs(5))
        val newer = InstallManager.saved.value.getValue("org.example.race")
        assertEquals(6L, newer.versionCode)
        assertEquals("content://downloads/21", newer.uri)
        assertEquals(listOf("content://downloads/22"), deleted)
        assertEquals(InstallManager.HandOver.Saved(newer), result)
    }

    @Test
    fun aFileThatCannotBeSavedFailsWithTheReason() = runBlocking {
        InstallManager.saver = { _, _, _ -> throw java.io.IOException("No room") }
        val result = InstallManager.handOver(
            context, "owner/example", null, "org.example", listOf("base.apk" to aFile()),
            rootWanted = false, saveAs = saveAs()
        )
        assertEquals(InstallManager.HandOver.Failed(InstallState.Failed(FailReason.SAVE, "No room")), result)
    }

    /** Answers `id` as root, and every `pm` step with success except the commit, which says [commit]. */
    private fun rootAnswering(commit: Root.Outcome.Ran) {
        Root.transport = { command, stdin, _, _ ->
            stdin?.invoke(java.io.ByteArrayOutputStream())
            when {
                command == "id" -> Root.Outcome.Ran(0, "uid=0(root) gid=0(root)", "")
                command.startsWith("pm install-create") -> Root.Outcome.Ran(0, "Success: created install session [77]\n", "")
                command.startsWith("pm install-commit") -> commit
                else -> Root.Outcome.Ran(0, "Success\n", "")
            }
        }
    }

    @Test
    fun rootSuccessSettlesInstalledAndFailureShowsTheMessage() = runBlocking {
        rootAnswering(Root.Outcome.Ran(0, "Success\n", ""))
        assertEquals(
            InstallManager.HandOver.Installed,
            InstallManager.handOver(context, "owner/example", null, "org.example", listOf("base.apk" to aFile()), true, saveAs())
        )
        val message = "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package org.example signatures do not match"
        rootAnswering(Root.Outcome.Ran(1, "Failure [$message]\n", ""))
        assertEquals(
            InstallManager.HandOver.Failed(InstallState.Failed(FailReason.SIGNATURE_MISMATCH, message)),
            InstallManager.handOver(context, "owner/example", null, "org.example", listOf("base.apk" to aFile()), true, saveAs())
        )
        // A root install that failed is not saved instead: the system has said why.
        assertEquals(emptyList<String>(), saves)
    }

    @Test
    fun aRecordFromAnEarlierRunIsReplacedAndItsFileDeleted() = runBlocking {
        // Written down by an earlier run, or kept after the user installed it: not among those shown.
        val earlier = SavedApk("org.example.earlier", 5, "1.5", "content://downloads/21", "owner/earlier")
        context.getSharedPreferences("saved_apks", 0).edit()
            .putString("records", SavedApks.toJson(mapOf(earlier.packageName to earlier))).apply()
        saving("content://downloads/22")
        InstallManager.handOver(context, "owner/earlier", null, "org.example.earlier", listOf("base.apk" to aFile()), false, saveAs(6))
        assertEquals(listOf("content://downloads/21"), deleted)
    }
}
