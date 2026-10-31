package org.jarsi.arkstore.install

import android.content.Intent
import android.content.pm.PackageInstaller
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manager is one for the process, so each test has an app of its own and settles what it
 * leaves on its way.
 */
class InstallManagerTest {
    private val repos = mutableListOf<String>()

    private fun pending(repo: String, sessionId: Int): PendingConfirmation {
        repos += repo
        return PendingConfirmation(repo, sessionId, "org.example.$sessionId", "Example", "#Intent;end")
    }

    @After
    fun settleWhatIsLeft() {
        repos.forEach { InstallManager.settle(it, PackageInstaller.STATUS_FAILURE_ABORTED, null, null) {} }
    }

    @Test
    fun anAbortBeforeThePromptCouldBeAnsweredIsTheSystemsNotTheUsers() {
        val now = 1_000_000L
        // Nothing was presented: the system gave up on its own.
        assertTrue(abortedBySystem(presentedAt = null, now = now, graceMillis = 1500))
        // Presented a moment ago: nobody could have read and refused it yet.
        assertTrue(abortedBySystem(presentedAt = now - 1499, now = now, graceMillis = 1500))
        // Presented long enough ago: the user refused.
        assertFalse(abortedBySystem(presentedAt = now - 1500, now = now, graceMillis = 1500))
        assertFalse(abortedBySystem(presentedAt = now - 60_000, now = now, graceMillis = 1500))
    }

    @Test
    fun aRestoredConfirmationIsAskedAgainUnderItsSessionUntilItsResultComes() {
        val pending = pending("owner/restored", 71)
        assertTrue(InstallManager.restore(pending, Intent()))
        assertEquals(InstallState.Installing, InstallManager.states.value[pending.repo])
        assertTrue(InstallManager.confirmations.holds(pending.repo))

        assertNotNull(InstallManager.settle(pending.repo, PackageInstaller.STATUS_SUCCESS, null, 71) {})
        assertNull(InstallManager.states.value[pending.repo])
        assertFalse(InstallManager.confirmations.holds(pending.repo))
    }

    @Test
    fun aResultThatCameFirstLeavesNothingToRestore() {
        // The user confirmed from the notification, and the result arrived while the record
        // was being read: the install is done, and must not come back as installing.
        val pending = pending("owner/settled", 72)
        assertNotNull(InstallManager.settle(pending.repo, PackageInstaller.STATUS_SUCCESS, null, 72) {})

        assertFalse(InstallManager.restore(pending, Intent()))
        assertNull(InstallManager.states.value[pending.repo])
        assertFalse(InstallManager.confirmations.holds(pending.repo))
    }

    @Test
    fun anAttemptOnItsWayIsNotRestoredOver() {
        val pending = pending("owner/busy", 73)
        assertTrue(InstallManager.restore(pending, Intent()))

        assertFalse(InstallManager.restore(pending.copy(sessionId = 74), Intent()))
        // The result of the first settles it; the second was never begun.
        assertNotNull(InstallManager.settle(pending.repo, PackageInstaller.STATUS_SUCCESS, null, 73) {})
        assertNull(InstallManager.states.value[pending.repo])
    }

    @Test
    fun aResultOfAnEarlierSessionLeavesTheRestoredAttemptAlone() {
        val pending = pending("owner/earlier", 75)
        assertTrue(InstallManager.restore(pending, Intent()))

        assertNull(InstallManager.settle(pending.repo, PackageInstaller.STATUS_FAILURE, null, 70) {})
        assertEquals(InstallState.Installing, InstallManager.states.value[pending.repo])
        assertTrue(InstallManager.confirmations.holds(pending.repo))
    }

    @Test
    fun theRecordOfASettledAttemptGoesBeforeTheAppIsFreeForANewOne() {
        // A new attempt begun the moment the app is free writes a record of its own; the old
        // attempt's record must be gone by then, or the cleanup takes the new one's.
        val pending = pending("owner/freed", 76)
        assertTrue(InstallManager.restore(pending, Intent()))
        var stateAtCleanup: InstallState? = null

        assertNotNull(InstallManager.settle(pending.repo, PackageInstaller.STATUS_FAILURE, "gone", 76) {
            stateAtCleanup = InstallManager.states.value[pending.repo]
        })
        assertEquals(InstallState.Installing, stateAtCleanup)
        assertTrue(InstallManager.states.value[pending.repo] is InstallState.Failed)
    }

    @Test
    fun theRecordOfAnAttemptOnItsWayIsNotDroppedAsStale() {
        // The attempt committed its session after the sessions were listed, and wrote its record
        // before the records were read: its session is not among those listed, yet it is current.
        val current = pending("owner/current", 81)
        assertTrue(InstallManager.restore(current, Intent()))
        val back = pending("owner/back", 84)
        val gone = pending("owner/gone", 83)
        val forgotten = mutableListOf<String>()

        val known = InstallManager.restoreRecords(
            linkedMapOf(
                current.repo to current.copy(sessionId = 82).encode(),
                back.repo to back.encode(),
                gone.repo to gone.encode(),
                "owner/junk" to "not a record"
            ),
            alive = setOf(81, 84),
            prompt = { Intent() }
        ) { forgotten += it }

        assertEquals(listOf(gone.repo, "owner/junk"), forgotten)
        assertEquals(setOf(84), known)
        assertEquals(InstallState.Installing, InstallManager.states.value[current.repo])
        assertEquals(InstallState.Installing, InstallManager.states.value[back.repo])
        assertNull(InstallManager.states.value[gone.repo])
    }
}
