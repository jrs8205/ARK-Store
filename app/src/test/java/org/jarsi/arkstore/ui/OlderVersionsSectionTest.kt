package org.jarsi.arkstore.ui

import org.jarsi.arkstore.data.InstalledVersion
import org.jarsi.arkstore.data.OlderVersion
import org.jarsi.arkstore.data.testApp
import org.jarsi.arkstore.install.SavedApk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OlderVersionsSectionTest {

    private val app = testApp("alice/app", packageName = "org.alice", versionCode = 50)

    private fun file(versionCode: Long, repo: String = "alice/app", packageName: String = "org.alice") =
        SavedApk(packageName, versionCode, "1.$versionCode", "content://files/$versionCode", repo)

    @Test
    fun aSavedFileOfAnEarlierVersionIsToldInTheDetails() {
        val older = file(40)
        assertEquals(older, olderSaved(app, InstalledVersion(50, null), older))
        assertEquals(older, olderSaved(app, null, older))
        // Installed already, or the newest itself, which the row tells of: nothing to tell here.
        assertNull(olderSaved(app, InstalledVersion(40, null), older))
        assertNull(olderSaved(app, InstalledVersion(45, null), file(50)))
        assertNull(olderSaved(app, null, null))
        // Another project's file, or another package's, is not this app's.
        assertNull(olderSaved(app, null, file(40, repo = "bob/app")))
        assertNull(olderSaved(app, null, file(40, packageName = "org.bob")))
        // What the lists offer is not known: nothing says the file is an earlier one.
        assertNull(olderSaved(app.copy(versionCode = 0), null, older))
        // Another app holds the name: its version says nothing of this file.
        assertEquals(older, olderSaved(app, InstalledVersion(40, null, otherApp = true), older))
    }

    @Test
    fun onlyANewerVersionOfTheSameAppHasToGoFirst() {
        val older = file(40)
        assertTrue(needsUninstall(InstalledVersion(50, null), older))
        assertFalse(needsUninstall(InstalledVersion(30, null), older))
        assertFalse(needsUninstall(null, older))
        assertFalse(needsUninstall(InstalledVersion(50, null, otherApp = true), older))
    }

    @Test
    fun theVersionInstalledIsToldByItsName() {
        fun version(tag: String) = OlderVersion(tag, "", "", "", "", false, "app.apk", "https://example.invalid/app.apk", 1, 1)
        assertTrue(isInstalled(InstalledVersion(40, "1.4"), version("v1.4")))
        assertTrue(isInstalled(InstalledVersion(40, "1.4"), version("1.4")))
        assertFalse(isInstalled(InstalledVersion(40, "1.4"), version("v1.3")))
        // No name to go by, no app, or another app under the name.
        assertFalse(isInstalled(InstalledVersion(40, null), version("v1.4")))
        assertFalse(isInstalled(null, version("v1.4")))
        assertFalse(isInstalled(InstalledVersion(40, "1.4", otherApp = true), version("v1.4")))
    }

    @Test
    fun whatWasReadBelongsToTheVersionOfferedThen() {
        assertEquals(olderKey(app), olderKey(app.copy(description = "Other words")))
        assertNotEquals(olderKey(app), olderKey(app.copy(tag = "v51")))
        assertNotEquals(olderKey(app), olderKey(app.copy(fullName = "alice/other")))
    }
}
