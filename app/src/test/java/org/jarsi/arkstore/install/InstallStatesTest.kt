package org.jarsi.arkstore.install

import org.jarsi.arkstore.data.testApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(othersSettled(mapOf(repo to InstallState.Queued), store))
    }

    @Test
    fun aResultIsTakenForTheAttemptItBelongsTo() {
        // The system names the session; one of an earlier attempt is not this attempt's.
        assertTrue(belongsToCurrent(committed = 12, reported = 12))
        assertFalse(belongsToCurrent(committed = 13, reported = 12))
        // Without a name, or without an attempt to compare with, the result is taken.
        assertTrue(belongsToCurrent(committed = 13, reported = null))
        assertTrue(belongsToCurrent(committed = null, reported = 12))
    }

    @Test
    fun anInstallWhoseSessionIsGoneWithoutAWordIsStale() {
        val states = mapOf(
            "alice/app" to InstallState.Installing,
            "bob/app" to InstallState.Installing,
            "carol/app" to InstallState.Installing,
            "dave/app" to InstallState.Downloading(0.2f)
        )
        val sessions = mapOf("alice/app" to 11, "bob/app" to 12, "dave/app" to 14)

        // Carol's is being committed and has no session yet; Dave's is not installing.
        assertEquals(listOf("bob/app"), stale(states, sessions, alive = setOf(11)))
        assertEquals(emptyList<String>(), stale(states, sessions, alive = setOf(11, 12)))
    }
}
