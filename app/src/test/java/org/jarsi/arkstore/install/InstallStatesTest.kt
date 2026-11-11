package org.jarsi.arkstore.install

import kotlinx.coroutines.Job
import org.jarsi.arkstore.data.testApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallStatesTest {

    private val repo = "alice/app"
    private val failed = mapOf(repo to InstallState.Failed(FailReason.DOWNLOAD))
    private val downloading = mapOf(repo to InstallState.Downloading(0.5f))

    @Test
    fun anAppIsReservedOnceWhileItsInstallIsOnItsWay() {
        assertEquals(mapOf(repo to InstallState.Queued), reserved(emptyMap(), repo))
        assertEquals(mapOf(repo to InstallState.Queued), reserved(failed, repo))
        assertNull(reserved(downloading, repo))
        assertNull(reserved(mapOf(repo to InstallState.Queued), repo))
        assertNull(reserved(mapOf(repo to InstallState.Installing), repo))
        // Another app's install is no reason to wait.
        assertEquals(downloading + (repo + "2" to InstallState.Queued), reserved(downloading, repo + "2"))
    }

    @Test
    fun dismissingAFailureLeavesAnInstallThatHasBegunSinceAlone() {
        assertEquals(emptyMap<String, InstallState>(), dismissed(failed, repo))
        assertSame(downloading, dismissed(downloading, repo))
        assertSame(downloading, dismissed(downloading, "bob/app"))
    }

    @Test
    fun theStoreItselfIsInstalledAfterEveryOtherApp() {
        val store = testApp("jrs8205/ARK-Store", packageName = "org.jarsi.arkstore")
        val first = testApp("alice/app", packageName = "org.alice")
        val second = testApp("bob/app", packageName = "org.bob")
        val unknown = testApp("carol/app", packageName = null)

        assertEquals(
            listOf(first, second, unknown, store),
            installOrder(listOf(first, store, second, unknown), "org.jarsi.arkstore")
        )
        assertEquals(listOf(store), installOrder(listOf(store), "org.jarsi.arkstore"))
    }

    @Test
    fun othersHaveSettledWhenNoneIsQueuedDownloadingOrInstalling() {
        val store = "jrs8205/ARK-Store"
        assertTrue(othersSettled(emptyMap(), store))
        assertTrue(othersSettled(mapOf(store to InstallState.Queued), store))
        assertTrue(othersSettled(failed + (store to InstallState.Queued), store))
        assertFalse(othersSettled(downloading + (store to InstallState.Queued), store))
        assertFalse(othersSettled(mapOf(repo to InstallState.Installing), store))
        // A backup or a restore under way is cut off by the end of the process just the same.
        assertFalse(othersSettled(emptyMap(), store, working = true))
        assertFalse(othersSettled(mapOf(store to InstallState.Queued), store, working = true))
        assertFalse(othersSettled(mapOf(repo to InstallState.Queued), store))
    }

    @Test
    fun anOutcomeIsTakenByTheAttemptItSettles() {
        val mine = Settled(repo, attempt = 6, Outcome.CANCELLED)
        assertTrue(mine.isOf(repo, 6))
        assertFalse(mine.isOf(repo, 7))
        assertFalse(mine.isOf("bob/app", 6))
    }

    @Test
    fun aCancelNamingAnAttemptTouchesThatAttemptAlone() {
        val first = InstallJob(Job())
        val second = InstallJob(Job())
        assertTrue(isAttempt(first, first.id))
        assertFalse(isAttempt(first, second.id))
        assertTrue(isAttempt(second, null))
        assertFalse(isAttempt(null, first.id))
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun theFirstProgressOfADownloadIsAlwaysPublished() {
        // A size known from the response or the catalogue gives a step; neither gives none.
        assertEquals(50, downloadStep(read = 50, total = 100, fallback = 0))
        assertEquals(25, downloadStep(read = 50, total = -1, fallback = 200))
        assertEquals(UNKNOWN_STEP, downloadStep(read = 50, total = -1, fallback = 0))
        // The step before any is one that no download reports, so the first is a change.
        assertNotEquals(UNKNOWN_STEP, NO_STEP_YET)
        assertNotEquals(0, NO_STEP_YET)
    }

    @Test
    fun localKeysDoNotCollideWithCatalogueNames() {
        assertEquals("backup:org.example", localKey("org.example"))
        assertTrue(isLocal(localKey("org.example")))
        assertFalse(isLocal("owner/repo"))
        assertFalse(isLocal("fdroid:org.example"))
        assertFalse(isLocal("codeberg:owner/repo"))
        // Haptics for the list's rows: a local install of a package the list offers is not that row's.
        assertFalse(isLocal("org.example"))
    }
}
