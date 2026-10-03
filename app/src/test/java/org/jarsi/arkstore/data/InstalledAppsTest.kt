package org.jarsi.arkstore.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppsTest {

    private val offered = testApp(versionCode = 5)

    @Test
    fun comparesTheInstalledVersionWithTheOneOffered() {
        assertEquals(AppStatus.NOT_INSTALLED, InstalledApps.status(offered, null))
        assertEquals(
            AppStatus.UPDATE_AVAILABLE,
            InstalledApps.status(offered, InstalledVersion(4, "1.4"))
        )
        assertEquals(AppStatus.UP_TO_DATE, InstalledApps.status(offered, InstalledVersion(5, "1.5")))
        assertEquals(AppStatus.UP_TO_DATE, InstalledApps.status(offered, InstalledVersion(6, "1.6")))
    }

    @Test
    fun appSignedWithAnotherKeyIsNotAnUpdate() {
        val elsewhere = InstalledVersion(4, "1.4", otherSigner = true)
        assertEquals(AppStatus.OTHER_SIGNER, InstalledApps.status(offered, elsewhere))
    }

    @Test
    fun anotherProjectsBuildIsNotOfferedAsTheUpdateButIsNoConflictEither() {
        val presumed = InstalledVersion(4, "1.4", otherBuild = true)
        assertEquals(AppStatus.OTHER_BUILD, InstalledApps.status(offered, presumed))
        assertEquals(AppStatus.UP_TO_DATE, InstalledApps.status(offered, presumed.copy(versionCode = 5)))
        // A key seen to differ outranks the presumption.
        val seen = InstalledVersion(4, "1.4", otherSigner = true, otherBuild = true)
        assertEquals(AppStatus.OTHER_SIGNER, InstalledApps.status(offered, seen))
    }

    @Test
    fun anotherAppUnderThePackageNameLeavesThisOneNotInstalled() {
        // Whatever its version: it is not this app at all.
        val other = InstalledVersion(99, "53.3", otherSigner = true, label = "Google Play Store", otherApp = true)
        assertEquals(AppStatus.OTHER_APP, InstalledApps.status(offered, other))
        assertEquals(AppStatus.OTHER_APP, InstalledApps.status(offered, other.copy(versionCode = 1)))
    }

    @Test
    fun conflictBelongsToOneReleaseOfOneRepository() {
        val stable = testApp(fullName = "Owner/App")
        val beta = testApp(fullName = "Owner/App", prerelease = true)
        val elsewhere = testApp(fullName = "other/app")
        val keys = listOf(stable, beta, elsewhere).map { InstalledApps.conflictKey(it, "org.example") }
        assertEquals(3, keys.toSet().size)
        // GitHub names are not case sensitive.
        assertEquals(
            InstalledApps.conflictKey(stable, "org.example"),
            InstalledApps.conflictKey(testApp(fullName = "owner/app"), "org.example")
        )
    }

    @Test
    fun appSignedWithAnotherKeyButNotOlderIsSimplyInstalled() {
        val elsewhere = InstalledVersion(50, "1.5", otherSigner = true)
        assertEquals(AppStatus.UP_TO_DATE, InstalledApps.status(offered, elsewhere))
    }
}
