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
        // Another app holds the name: its version says nothing of this file.
        assertEquals(older, olderSaved(app, InstalledVersion(40, null, otherApp = true), older))
    }

    @Test
    fun aSavedFileIsToldByItsReleaseWhenTheListsDoNotKnowTheNewestFile() {
        // The manifest of the newest file could not be read: neither its version nor its
        // package is known, and an earlier version saved does not teach them.
        val unread = app.copy(versionCode = 0, packageName = null)
        val older = file(40).copy(tag = "v40")
        assertEquals(older, olderSaved(unread, null, older))
        assertEquals(older, olderSaved(app.copy(versionCode = 0), InstalledVersion(50, null), older))
        // The file of the release offered itself is not an earlier one.
        assertNull(olderSaved(unread, null, file(50).copy(tag = "v50")))
        // Nor is one whose release was not written down: nothing says which it is of.
        assertNull(olderSaved(unread, null, file(40)))
        // Installed already.
        assertNull(olderSaved(app.copy(versionCode = 0), InstalledVersion(40, null), older))
        assertNull(olderSaved(unread, null, older.copy(repo = "bob/app")))
        // With the versions known they decide: a file of another release that is newer than
        // the one offered, as a prerelease saved before they were turned off, is the row's.
        assertNull(olderSaved(app, null, file(60).copy(tag = "v60-beta")))
    }

    @Test
    fun whatIsInstalledIsLookedUpByTheSavedFilesPackageWhenTheListsKnowNone() {
        val unread = app.copy(versionCode = 0, packageName = null)
        val older = file(40).copy(tag = "v40")
        // The row of such an app tells of nothing installed; the file's own package does.
        assertEquals("org.alice", withPackageOf(unread, older)?.packageName)
        assertEquals(unread.fullName, withPackageOf(unread, older)?.fullName)
        // A newer version found under that package has to go first, and the file is told
        // with that; the same version found means there is nothing left to tell.
        assertEquals(older, olderSaved(unread, InstalledVersion(50, null), older))
        assertTrue(needsUninstall(InstalledVersion(50, null), older))
        assertNull(olderSaved(unread, InstalledVersion(40, null), older))
        // The lists know the package: the row has looked it up already.
        assertNull(withPackageOf(app, older))
        // Another project's file, or none.
        assertNull(withPackageOf(unread, older.copy(repo = "bob/app")))
        assertNull(withPackageOf(unread, null))
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
        assertEquals(olderKey(app, false), olderKey(app.copy(description = "Other words"), false))
        assertNotEquals(olderKey(app, false), olderKey(app.copy(tag = "v51"), false))
        assertNotEquals(olderKey(app, false), olderKey(app.copy(fullName = "alice/other"), false))
        // Read with prereleases or without: turning them on or off asks for the list again,
        // also when the version offered stays the same.
        assertNotEquals(olderKey(app, false), olderKey(app, true))
    }
}
