package org.jarsi.arkstore.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** A restore with root leaves alone the apps that are installed but hidden from the store. */
class RootGuardTest {

    private fun row(packageName: String, label: String, status: RestoreStatus = RestoreStatus.NOT_INSTALLED) = RestoreEntry(
        entry = BackupEntry(
            packageName = packageName,
            label = label,
            versionCode = 1,
            versionName = "1",
            installer = null,
            signers = emptyList(),
            updatedAt = 1,
            minSdk = 26,
            files = listOf(BackupFile("base.apk", 100))
        ),
        status = status,
        files = null
    )

    @Test
    fun onlyAConfirmedAbsenceIsRestored() = runBlocking {
        val hidden = row("org.example.hidden", "Hidden")
        val gone = row("org.example.gone", "Gone")
        val unknown = row("org.example.unknown", "Unknown")
        val answers = mapOf("org.example.hidden" to true, "org.example.gone" to false, "org.example.unknown" to null)
        val split = RootGuard.split(listOf(hidden, gone, unknown)) { answers.getValue(it) }
        assertEquals(listOf("Hidden"), split.installed)
        assertEquals(listOf("Unknown"), split.unchecked)
        assertEquals(listOf(gone), split.rest)
    }

    @Test
    fun noRootAtTheCheckRestoresNothingHidden() = runBlocking {
        val split = RootGuard.split(listOf(row("org.example.app", "Example"))) { null }
        assertEquals(emptyList<RestoreEntry>(), split.rest)
        assertEquals(listOf("Example"), split.unchecked)
    }

    @Test
    fun appsTheStoreSeesAreNotAsked() = runBlocking {
        val asked = ArrayList<String>()
        val older = row("org.example.older", "Older", RestoreStatus.OLDER_INSTALLED)
        val split = RootGuard.split(listOf(older)) { asked += it; null }
        assertEquals(emptyList<String>(), asked)
        assertEquals(listOf(older), split.rest)
    }
}
