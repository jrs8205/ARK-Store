package org.jarsi.arkstore.work

import java.io.IOException
import org.jarsi.arkstore.data.HttpStatusException
import org.jarsi.arkstore.data.RateLimitedException
import org.jarsi.arkstore.data.testApp
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
}
