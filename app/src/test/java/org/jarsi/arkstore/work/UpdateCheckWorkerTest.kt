package org.jarsi.arkstore.work

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.jarsi.arkstore.FakePreferences
import org.jarsi.arkstore.data.HttpStatusException
import org.jarsi.arkstore.data.RateLimitedException
import org.jarsi.arkstore.data.StoreApp
import org.jarsi.arkstore.data.UpdatePolicy
import org.jarsi.arkstore.data.testApp
import org.jarsi.arkstore.install.FailReason
import org.jarsi.arkstore.install.InstallManager
import org.jarsi.arkstore.install.Root
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckWorkerTest {

    @Test
    fun usedUpQuotaAndUnavailableSourcesAreNotRetried() {
        assertTrue(isLasting(RateLimitedException(0)))
        assertTrue(isLasting(HttpStatusException(404)))
        assertTrue(isLasting(HttpStatusException(410)))
        // A repository GitHub has blocked answers 403 for good.
        assertTrue(isLasting(HttpStatusException(403)))
    }

    @Test
    fun serverErrorsAndThrottlingAreRetried() {
        assertFalse(isLasting(HttpStatusException(500)))
        assertFalse(isLasting(HttpStatusException(502)))
        assertFalse(isLasting(HttpStatusException(503)))
        assertFalse(isLasting(HttpStatusException(429)))
        // GitHub's secondary limit answers 403 and says when to come back.
        assertFalse(isLasting(HttpStatusException(403, retryAfter = true)))
    }

    @Test
    fun networkFailuresAreRetried() {
        assertFalse(isLasting(IOException("timeout")))
    }

    @Test
    fun updateIsAnnouncedOnce() {
        val waiting = setOf(announcement(testApp(fullName = "alice/app", versionCode = 5)))
        assertTrue(newToAnnounce(waiting, announced = emptySet()))
        assertFalse(newToAnnounce(waiting, announced = waiting))
        assertFalse(newToAnnounce(emptySet(), announced = waiting))
    }

    @Test
    fun newerVersionOfAnAnnouncedAppIsAnnouncedAgain() {
        val announced = setOf(announcement(testApp(fullName = "alice/app", versionCode = 5)))
        val waiting = setOf(announcement(testApp(fullName = "alice/app", versionCode = 6)))
        assertTrue(newToAnnounce(waiting, announced))
    }

    @Test
    fun sameNamedRepositoriesOfDifferentOwnersAreToldApart() {
        assertNotEquals(
            announcement(testApp(fullName = "alice/app", versionCode = 5)),
            announcement(testApp(fullName = "bob/app", versionCode = 5))
        )
    }

    @Test
    fun updatesInstalledOrWaitingForTheUserAreNotAnnounced() {
        val installed = testApp(fullName = "alice/app", versionCode = 5)
        val waiting = testApp(fullName = "bob/app", versionCode = 2)
        val failed = testApp(fullName = "carol/app", versionCode = 3)
        val other = testApp(fullName = "dan/app", versionCode = 4)
        val updates = listOf(installed, waiting, failed, other)
        val settled = setOf(installed.fullName, waiting.fullName)
        assertEquals(listOf(failed, other), toAnnounce(updates, settled))
        assertEquals(updates, toAnnounce(updates, emptySet()))
    }

    @Test
    fun anUpdateTheUserHasSinceSkippedOrHeldIsNeitherTriedNorAnnounced() {
        val first = testApp("alice/app", packageName = "org.alice", versionCode = 2)
        val second = testApp("bob/app", packageName = "org.bob", versionCode = 5)
        val policy = UpdatePolicy.NONE.skip(first).hold("org.bob", true)
        assertEquals(emptyList<StoreApp>(), wanted(listOf(first, second), policy))
        assertEquals(listOf(first), wanted(listOf(first, second), UpdatePolicy.NONE.hold("org.bob", true)))
        assertEquals(listOf(second), wanted(listOf(first, second), UpdatePolicy.NONE.skip(first)))
    }

    @Test
    fun anUnaskedInstallThatFailedIsNotTriedAgainBeforeANewVersion() {
        val broken = testApp(fullName = "alice/app", versionCode = 5)
        val fixed = testApp(fullName = "alice/app", versionCode = 6)
        val other = testApp(fullName = "bob/app", versionCode = 2)
        val failed = setOf(announcement(broken), announcement(testApp(fullName = "gone/app", versionCode = 1)))
        assertEquals(listOf(other), toTry(listOf(broken, other), failed))
        assertEquals(listOf(fixed, other), toTry(listOf(fixed, other), failed))
        // What is no longer offered is forgotten, so that the set does not grow for ever.
        assertEquals(setOf(announcement(broken)), stillFailed(failed, listOf(broken, other)))
    }

    @Test
    fun rootIsAskedOnlyWhenAnUpdateMayBeInstalledUnasked() {
        val app = testApp(fullName = "alice/app", versionCode = 2)
        val on = AutoUpdate(enabled = true, unmeteredOnly = true, chargingOnly = true)
        assertFalse(asksRoot(on.copy(enabled = false), listOf(app), unmetered = true, charging = true))
        assertFalse(asksRoot(on, emptyList(), unmetered = true, charging = true))
        assertTrue(asksRoot(on, listOf(app), unmetered = true, charging = true))
        // Nor when the network or the charger would stop the install anyway: asking makes
        // some root apps show a note, every four hours, for nothing.
        assertFalse(asksRoot(on, listOf(app), unmetered = false, charging = true))
        assertFalse(asksRoot(on, listOf(app), unmetered = true, charging = false))
        assertTrue(asksRoot(on.copy(unmeteredOnly = false, chargingOnly = false), listOf(app), unmetered = false, charging = false))
    }

    @Test
    fun turningRootOffStopsTheUnaskedInstallsOfARun() {
        val settings = AutoUpdate(enabled = true, unmeteredOnly = false, chargingOnly = false)
        assertTrue(unaskedAllowed(settings, rootGiven = true, rootOn = true, rootRefused = false, unmetered = true, charging = false))
        assertFalse(unaskedAllowed(settings, rootGiven = true, rootOn = false, rootRefused = false, unmetered = true, charging = false))
        assertFalse(unaskedAllowed(settings, rootGiven = false, rootOn = true, rootRefused = false, unmetered = true, charging = false))
    }

    @Test
    fun aRefusalOfRootStopsTheRunEvenWhenItsOutcomeWasNotWaitedFor() {
        // The refusal is written down before the attempt's outcome is told, so a run whose wait
        // ran out at that very moment, and so never had the outcome, stops all the same.
        val settings = AutoUpdate(enabled = true, unmeteredOnly = false, chargingOnly = false)
        assertFalse(unaskedAllowed(settings, rootGiven = true, rootOn = true, rootRefused = true, unmetered = true, charging = false))
    }

    /** A context of preferences alone, which is all that root's setting asks of it. */
    private class PrefsContext : ContextWrapper(null) {
        val prefs = HashMap<String, FakePreferences>()
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "org.jarsi.arkstore"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            prefs.getOrPut(name!!) { FakePreferences() }
    }

    @Test
    fun aRefusalNotedWhileARunAsksForRootIsKeptAndStopsTheRun() = runBlocking {
        val context = PrefsContext()
        Root.setEnabled(context, true)
        val before = InstallManager.rootRefusals
        // The user's own install is refused root while the run's question is on its way.
        Root.transport = { _, _, _, _ ->
            InstallManager.setRootLost(context, true)
            Root.Outcome.Ran(0, "uid=0(root) gid=0(root)", "")
        }
        try {
            assertTrue(rootReady(context))
            assertTrue(Root.lost(context))
            assertTrue(InstallManager.rootLost.value)
            assertTrue(InstallManager.rootRefusedSince(before))
        } finally {
            Root.transport = null
            InstallManager.setRootLost(context, false)
        }
    }

    @Test
    fun aRefusalFromBeforeTheRunDoesNotStopIt() {
        val context = PrefsContext()
        InstallManager.setRootLost(context, true)
        try {
            val before = InstallManager.rootRefusals
            assertFalse(InstallManager.rootRefusedSince(before))
            // Root given again says nothing new of a refusal.
            InstallManager.setRootLost(context, false)
            assertFalse(InstallManager.rootRefusedSince(before))
            InstallManager.setRootLost(context, true)
            assertTrue(InstallManager.rootRefusedSince(before))
        } finally {
            InstallManager.setRootLost(context, false)
        }
    }

    @Test
    fun rootRefusedWhenARunAsksKeepsTheRefusal() = runBlocking {
        val context = PrefsContext()
        Root.setEnabled(context, true)
        InstallManager.setRootLost(context, true)
        Root.transport = { _, _, _, _ -> Root.Outcome.Ran(1, "", "Permission denied") }
        try {
            assertFalse(rootReady(context))
            assertTrue(Root.lost(context))
        } finally {
            Root.transport = null
            InstallManager.setRootLost(context, false)
        }
    }

    @Test
    fun anInstallThatHadNoRootIsTriedAgain() {
        assertFalse(failureLasts(FailReason.NO_ROOT))
        assertTrue(failureLasts(FailReason.INVALID_APK))
        assertTrue(failureLasts(null))
        // Root that is refused ends the run: the next install would be refused as well.
        assertTrue(endsRun(FailReason.NO_ROOT))
        assertFalse(endsRun(FailReason.INVALID_APK))
    }

    @Test
    fun aDownloadThatFailedIsTriedAgainAtTheNextRun() {
        // A network that dropped or a server that did not answer says nothing of the version.
        assertFalse(failureLasts(FailReason.DOWNLOAD))
        // The run goes on to the next app: whether the network is still there is asked before each.
        assertFalse(endsRun(FailReason.DOWNLOAD))
        assertTrue(failureLasts(FailReason.SIGNATURE_MISMATCH))
        assertTrue(failureLasts(FailReason.INSTALL))
    }
}
