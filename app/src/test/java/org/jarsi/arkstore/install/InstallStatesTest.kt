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
        assertFalse(othersSettled(mapOf(repo to InstallState.Queued), store))
    }

    @Test
    fun aResultIsTakenForTheAttemptItBelongsTo() {
        // The system names the session; one of an earlier attempt is not this attempt's.
        assertTrue(belongsToCurrent(committed = 12, reported = 12, downloading = false))
        assertFalse(belongsToCurrent(committed = 13, reported = 12, downloading = false))
        // Without a name, the result is taken.
        assertTrue(belongsToCurrent(committed = 13, reported = null, downloading = false))
        // An attempt still downloading has no session yet: a named one is an earlier attempt's.
        assertFalse(belongsToCurrent(committed = null, reported = 12, downloading = true))
        // Nothing on its way, as after the process was restarted: the result is taken.
        assertTrue(belongsToCurrent(committed = null, reported = 12, downloading = false))
    }

    @Test
    fun anAttemptIsOnItsWayWithoutASessionFromItsReservationOn() {
        // The reservation comes before the job is stored; a result the system names while an
        // attempt is anywhere on its way without a session is an earlier attempt's.
        assertTrue(attemptWithoutSession(committed = null, state = InstallState.Queued))
        assertTrue(attemptWithoutSession(committed = null, state = InstallState.Downloading(0.1f)))
        assertTrue(attemptWithoutSession(committed = null, state = InstallState.Installing))
        assertFalse(attemptWithoutSession(committed = null, state = null))
        assertFalse(attemptWithoutSession(committed = null, state = InstallState.Failed(FailReason.INSTALL)))
        assertFalse(attemptWithoutSession(committed = 12, state = InstallState.Installing))
    }

    @Test
    fun aResultSettlesTheStateOfItsOwnAttemptAlone() {
        val installing = mapOf(repo to InstallState.Installing)
        val queued = mapOf(repo to InstallState.Queued)
        // The result of the attempt committed takes down the state that attempt left installing.
        assertEquals(emptyMap<String, InstallState>(), afterResult(installing, repo, null, owned = true))
        assertEquals(failed, afterResult(installing, repo, InstallState.Failed(FailReason.DOWNLOAD), owned = true))
        // A new attempt has begun since the result passed its check: it is left alone.
        assertSame(queued, afterResult(queued, repo, InstallState.Failed(FailReason.INSTALL), owned = true))
        assertSame(downloading, afterResult(downloading, repo, null, owned = true))
        // A result with nothing committed, as after the process was restarted, settles when
        // nothing is on its way, and leaves an attempt begun since alone.
        assertEquals(failed, afterResult(emptyMap(), repo, InstallState.Failed(FailReason.DOWNLOAD), owned = false))
        assertEquals(emptyMap<String, InstallState>(), afterResult(failed, repo, null, owned = false))
        assertSame(queued, afterResult(queued, repo, null, owned = false))
        assertSame(installing, afterResult(installing, repo, null, owned = false))
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
