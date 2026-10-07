package org.jarsi.arkstore.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class InstallStatesTest {

    private val repo = "alice/app"
    private val failed = mapOf(repo to InstallState.Failed(FailReason.DOWNLOAD))
    private val downloading = mapOf(repo to InstallState.Downloading(0.5f))

    @Test
    fun anAppIsReservedOnceWhileItsInstallIsOnItsWay() {
        assertEquals(mapOf(repo to InstallState.Downloading(0f)), reserved(emptyMap(), repo))
        assertEquals(mapOf(repo to InstallState.Downloading(0f)), reserved(failed, repo))
        assertNull(reserved(downloading, repo))
        assertNull(reserved(mapOf(repo to InstallState.Installing), repo))
        // Another app's install is no reason to wait.
        assertEquals(downloading + (repo + "2" to InstallState.Downloading(0f)), reserved(downloading, repo + "2"))
    }

    @Test
    fun dismissingAFailureLeavesAnInstallThatHasBegunSinceAlone() {
        assertEquals(emptyMap<String, InstallState>(), dismissed(failed, repo))
        assertSame(downloading, dismissed(downloading, repo))
        assertSame(downloading, dismissed(downloading, "bob/app"))
    }
}
