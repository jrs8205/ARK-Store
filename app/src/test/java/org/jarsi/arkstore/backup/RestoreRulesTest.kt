package org.jarsi.arkstore.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreRulesTest {

    private val key = "a".repeat(64)
    private val otherKey = "b".repeat(64)

    private val entry = BackupEntry(
        packageName = "org.example.app",
        label = "Example",
        versionCode = 12,
        versionName = "1.2",
        installer = null,
        signers = listOf(key),
        updatedAt = 1,
        minSdk = 26,
        files = listOf(BackupFile("base.apk", 100), BackupFile("split_x.apk", 10))
    )

    private val present = mapOf("base.apk" to 100L, "split_x.apk" to 10L, "app.json" to 5L)

    private fun status(
        installed: Long? = null,
        keysHeld: Set<String>? = setOf(key),
        present: Map<String, Long>? = this.present,
        sdk: Int = 34,
        entry: BackupEntry = this.entry
    ) = RestoreRules.status(entry, installed, keysHeld, present, sdk)

    @Test
    fun filesMissingIsNotSelectable() {
        assertEquals(RestoreStatus.FILES_MISSING, status(present = null))
        assertEquals(RestoreStatus.FILES_MISSING, status(present = mapOf("base.apk" to 100L)))
        // A file of another size than the entry says is not the file.
        assertEquals(RestoreStatus.FILES_MISSING, status(present = present + ("split_x.apk" to 11L)))
        assertFalse(RestoreStatus.FILES_MISSING.selectable)
        assertFalse(RestoreStatus.FILES_MISSING.preselected)
    }

    @Test
    fun needsNewerAndroid() {
        assertEquals(RestoreStatus.NEEDS_NEWER_ANDROID, status(sdk = 25))
        assertEquals(RestoreStatus.NOT_INSTALLED, status(sdk = 26))
        assertEquals(RestoreStatus.NOT_INSTALLED, status(sdk = 25, entry = entry.copy(minSdk = null)))
        assertFalse(RestoreStatus.NEEDS_NEWER_ANDROID.selectable)
    }

    @Test
    fun notInstalledIsPreselected() {
        assertEquals(RestoreStatus.NOT_INSTALLED, status(installed = null, keysHeld = null))
        assertTrue(RestoreStatus.NOT_INSTALLED.selectable)
        assertTrue(RestoreStatus.NOT_INSTALLED.preselected)
    }

    @Test
    fun olderWithSameKeyIsPreselected() {
        assertEquals(RestoreStatus.OLDER_INSTALLED, status(installed = 11, keysHeld = setOf(key, otherKey)))
        assertTrue(RestoreStatus.OLDER_INSTALLED.selectable)
        assertTrue(RestoreStatus.OLDER_INSTALLED.preselected)
    }

    @Test
    fun olderWithUnknownKeysIsPreselected() {
        assertEquals(RestoreStatus.OLDER_INSTALLED, status(installed = 11, keysHeld = null))
        assertEquals(RestoreStatus.OLDER_INSTALLED, status(installed = 11, keysHeld = setOf(otherKey), entry = entry.copy(signers = emptyList())))
    }

    @Test
    fun otherSignerIsNotSelectable() {
        assertEquals(RestoreStatus.OTHER_SIGNER, status(installed = 11, keysHeld = setOf(otherKey)))
        assertEquals(RestoreStatus.OTHER_SIGNER, status(installed = 12, keysHeld = setOf(otherKey)))
        assertEquals(RestoreStatus.OTHER_SIGNER, status(installed = 13, keysHeld = setOf(otherKey)))
        assertFalse(RestoreStatus.OTHER_SIGNER.selectable)
    }

    @Test
    fun sameAndNewerInstalled() {
        assertEquals(RestoreStatus.SAME_INSTALLED, status(installed = 12))
        assertTrue(RestoreStatus.SAME_INSTALLED.selectable)
        assertFalse(RestoreStatus.SAME_INSTALLED.preselected)
        assertEquals(RestoreStatus.NEWER_INSTALLED, status(installed = 13))
        assertFalse(RestoreStatus.NEWER_INSTALLED.selectable)
        assertFalse(RestoreStatus.NEWER_INSTALLED.preselected)
    }

    @Test
    fun preselectionFollowsInstaller() {
        assertFalse(Installers.preselected(Installers.PLAY))
        assertTrue(Installers.preselected(null))
        assertTrue(Installers.preselected("org.fdroid.fdroid"))
        assertTrue(Installers.preselected("org.jarsi.arkstore"))
        assertTrue(Installers.preselected("com.aurora.store"))
    }

    @Test
    fun knownNames() {
        assertEquals("Google Play", Installers.knownName(Installers.PLAY))
        assertEquals("F-Droid", Installers.knownName("org.fdroid.fdroid"))
        assertEquals("F-Droid", Installers.knownName("org.fdroid.basic"))
        assertEquals("ARK-Store", Installers.knownName("org.jarsi.arkstore"))
        assertEquals("Aurora Store", Installers.knownName("com.aurora.store"))
        assertEquals("Obtainium", Installers.knownName("dev.imranr.obtainium"))
        assertEquals("Droid-ify", Installers.knownName("com.looker.droidify"))
        assertEquals("Neo Store", Installers.knownName("com.machiav3lli.fdroid"))
        assertEquals("Galaxy Store", Installers.knownName("com.sec.android.app.samsungapps"))
        // The system's own installer is the user installing by hand, which is no name.
        assertNull(Installers.knownName("com.google.android.packageinstaller"))
        assertNull(Installers.knownName("com.android.packageinstaller"))
        assertNull(Installers.knownName(null))
        assertNull(Installers.knownName("com.unknown.store"))
    }
}
