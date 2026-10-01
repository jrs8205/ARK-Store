package org.jarsi.arkstore.work

import java.io.IOException
import org.jarsi.arkstore.data.HttpStatusException
import org.jarsi.arkstore.data.RateLimitedException
import org.jarsi.arkstore.data.testApp
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
}
