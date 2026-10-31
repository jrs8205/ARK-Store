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
        repos.forEach { InstallManager.settle(it, PackageInstaller.STATUS_FAILURE_ABORTED, null, null) }
    }

    @Test
    fun aRestoredConfirmationIsAskedAgainUnderItsSessionUntilItsResultComes() {
        val pending = pending("owner/restored", 71)
        assertTrue(InstallManager.restore(pending, Intent()))
        assertEquals(InstallState.Installing, InstallManager.states.value[pending.repo])
        assertTrue(InstallManager.confirmations.holds(pending.repo))

        assertNotNull(InstallManager.settle(pending.repo, PackageInstaller.STATUS_SUCCESS, null, 71))
        assertNull(InstallManager.states.value[pending.repo])
        assertFalse(InstallManager.confirmations.holds(pending.repo))
    }

    @Test
    fun aResultThatCameFirstLeavesNothingToRestore() {
        // The user confirmed from the notification, and the result arrived while the record
        // was being read: the install is done, and must not come back as installing.
        val pending = pending("owner/settled", 72)
        assertNotNull(InstallManager.settle(pending.repo, PackageInstaller.STATUS_SUCCESS, null, 72))

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
        assertNotNull(InstallManager.settle(pending.repo, PackageInstaller.STATUS_SUCCESS, null, 73))
        assertNull(InstallManager.states.value[pending.repo])
    }

    @Test
    fun aResultOfAnEarlierSessionLeavesTheRestoredAttemptAlone() {
        val pending = pending("owner/earlier", 75)
        assertTrue(InstallManager.restore(pending, Intent()))

        assertNull(InstallManager.settle(pending.repo, PackageInstaller.STATUS_FAILURE, null, 70))
        assertEquals(InstallState.Installing, InstallManager.states.value[pending.repo])
        assertTrue(InstallManager.confirmations.holds(pending.repo))
    }
}
