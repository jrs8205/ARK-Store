package org.jarsi.arkstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun newestVersionThisAndroidCannotRunIsNoUpdate() {
        val older = InstalledVersion(4, "1.4")
        assertEquals(AppStatus.NEEDS_NEWER_ANDROID, InstalledApps.status(offered, older, runs = false))
        // Whatever its key: nothing could be installed anyway.
        assertEquals(
            AppStatus.NEEDS_NEWER_ANDROID,
            InstalledApps.status(offered, older.copy(otherSigner = true), runs = false)
        )
        // The installed version is the one offered, or newer: nothing is missing.
        assertEquals(AppStatus.UP_TO_DATE, InstalledApps.status(offered, InstalledVersion(5, "1.5"), runs = false))
        assertEquals(AppStatus.NOT_INSTALLED, InstalledApps.status(offered, null, runs = false))
        assertEquals(
            AppStatus.OTHER_APP,
            InstalledApps.status(offered, older.copy(otherApp = true), runs = false)
        )
    }

    @Test
    fun appSignedWithAnotherKeyIsNotAnUpdate() {
        val elsewhere = InstalledVersion(4, "1.4", otherSigner = true)
        assertEquals(AppStatus.OTHER_SIGNER, InstalledApps.status(offered, elsewhere))
    }

    @Test
    fun translatedCatalogueNamesDoNotIdentifyAnotherApp() {
        for (source in listOf(StoreApp.SOURCE_FDROID, StoreApp.SOURCE_IZZY)) {
            val app = offered.copy(source = source, label = "Calculator", signer = "b")
            val installed = InstalledApps.compareIdentity(
                app, InstalledVersion(4, "1.4", label = "Calculator"), setOf("a"), "Laskin"
            )
            assertEquals(AppStatus.OTHER_SIGNER, InstalledApps.status(app, installed))
            assertFalse(installed.otherApp)
            assertEquals("Calculator", installed.label)
            // The same classification survives the stored catalogue's JSON round trip.
            assertEquals(installed, InstalledApps.compareIdentity(
                StoreApp.fromJson(app.toJson()), installed, setOf("a"), "Laskin"
            ))
        }
    }

    @Test
    fun repositoryDefaultNamesIgnoreTheDevicesTranslation() {
        for (source in listOf(StoreApp.SOURCE_GITHUB, StoreApp.SOURCE_CODEBERG, StoreApp.SOURCE_GITLAB)) {
            val app = offered.copy(source = source, label = "Calculator", signer = "b")
            val installed = InstalledApps.compareIdentity(
                app, InstalledVersion(4, "1.4", label = "Laskin"), setOf("a"), " calculator "
            )
            assertEquals(AppStatus.OTHER_SIGNER, InstalledApps.status(app, installed))
            assertFalse(installed.otherApp)
        }
    }

    @Test
    fun onlyKnownDifferentDefaultNamesAndKeysIdentifyAnotherApp() {
        val app = offered.copy(label = "Companion", signer = "b")
        val before = InstalledVersion(99, "99", label = "Play Store")
        val different = InstalledApps.compareIdentity(app, before, setOf("a"), "Play Store")
        assertEquals(AppStatus.OTHER_APP, InstalledApps.status(app, different))
        for (label in listOf(null, "", "  ")) {
            assertFalse(InstalledApps.compareIdentity(app.copy(label = label), before, setOf("a"), "Play Store").otherApp)
            assertFalse(InstalledApps.compareIdentity(app, before, setOf("a"), label).otherApp)
        }
        assertFalse(InstalledApps.compareIdentity(app, before, setOf("b"), "Play Store").otherApp)
        assertFalse(InstalledApps.compareIdentity(app, before, null, "Play Store").otherApp)
        assertFalse(InstalledApps.compareIdentity(app.copy(signer = null), before, setOf("a"), "Play Store").otherApp)
    }

    @Test
    fun oneSharedKeyDoesNotEraseAMultipleSignerConflict() {
        val app = offered.copy(label = "Calculator", signer = "a")
        for (remembered in listOf(false, true)) {
            val installed = InstalledApps.compareIdentity(
                app, InstalledVersion(4, "1.4", otherSigner = remembered), setOf("a", "b"), "Calculator"
            )
            assertFalse(installed.sameSigner)
            assertTrue(installed.otherSigner)
            assertFalse(installed.otherApp)
            assertEquals(AppStatus.OTHER_SIGNER, InstalledApps.status(app, installed))
        }
    }

    @Test
    fun anUpdateTheUserHasSkippedIsToldApartFromOneOnOffer() {
        val app = offered.copy(signer = "a")
        val installed = InstalledVersion(4, "1.4", sameSigner = true, installer = "store", label = "Calculator")
        assertEquals(AppStatus.UPDATE_SKIPPED, InstalledApps.status(app, installed, skipped = true))
        assertEquals(AppStatus.UPDATE_AVAILABLE, InstalledApps.status(app, installed, skipped = false))
        // Nothing to skip when there is no update, or when it cannot be installed anyway.
        assertEquals(AppStatus.UP_TO_DATE, InstalledApps.status(app.copy(versionCode = 4), installed, skipped = true))
        assertEquals(AppStatus.NEEDS_NEWER_ANDROID, InstalledApps.status(app, installed, runs = false, skipped = true))
        assertEquals(AppStatus.OTHER_SIGNER, InstalledApps.status(app, installed.copy(otherSigner = true, sameSigner = false), skipped = true))
    }

    @Test
    fun anExactSignerMatchOverridesAnEarlierConflict() {
        val app = offered.copy(label = "Calculator", signer = "a")
        val before = InstalledVersion(4, "1.4", otherSigner = true, beta = true, installer = "store", label = "Laskin")
        val installed = InstalledApps.compareIdentity(app, before, setOf("a"), "Calculator")
        assertTrue(installed.sameSigner)
        assertFalse(installed.otherSigner)
        assertEquals(AppStatus.UPDATE_AVAILABLE, InstalledApps.status(app, installed))
        assertEquals(before.copy(otherSigner = false, sameSigner = true), installed)
    }

    @Test
    fun unknownSignersPreserveARememberedConflict() {
        val app = offered.copy(label = "Calculator", signer = "a")
        val before = InstalledVersion(4, "1.4", otherSigner = true)
        for ((offer, signers) in listOf(app to null, app to emptySet(), app.copy(signer = null) to setOf("a"))) {
            val installed = InstalledApps.compareIdentity(offer, before, signers, "Another name")
            assertFalse(installed.sameSigner)
            assertFalse(installed.otherApp)
            assertEquals(AppStatus.OTHER_SIGNER, InstalledApps.status(offer, installed))
        }
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
