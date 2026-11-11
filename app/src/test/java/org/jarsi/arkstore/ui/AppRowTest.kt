package org.jarsi.arkstore.ui

import org.jarsi.arkstore.R
import org.jarsi.arkstore.data.AppStatus
import org.jarsi.arkstore.data.InstalledVersion
import org.jarsi.arkstore.data.testApp
import org.jarsi.arkstore.install.FailReason
import org.jarsi.arkstore.install.SavedApk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRowTest {

    private val stable = testApp(versionCode = 5)

    private fun row(installed: Long?, betaVersion: Long?) = AppRow(
        app = stable,
        installed = installed?.let { InstalledVersion(it, null) },
        status = AppStatus.UP_TO_DATE,
        betaVersion = betaVersion
    )

    @Test
    fun installedPrereleaseIsABeta() {
        assertTrue(row(installed = 8, betaVersion = 8).betaInstalled)
        // An earlier beta than the newest one is a beta all the same.
        assertTrue(row(installed = 6, betaVersion = 8).betaInstalled)
    }

    @Test
    fun newerVersionFromElsewhereIsNotCalledABeta() {
        val fromAnotherStore = row(installed = 50, betaVersion = 8)
        assertTrue(fromAnotherStore.newerInstalled)
        assertFalse(fromAnotherStore.betaInstalled)
        assertFalse(row(installed = 6, betaVersion = null).betaInstalled)
    }

    @Test
    fun versionOfferedOrOlderIsNeither() {
        assertFalse(row(installed = 5, betaVersion = 8).newerInstalled)
        assertFalse(row(installed = 4, betaVersion = 8).betaInstalled)
        assertFalse(row(installed = null, betaVersion = 8).newerInstalled)
    }

    private val record = SavedApk("org.example.app", 5, "1.5", "content://downloads/1", "owner/app")

    private fun withStatus(status: AppStatus, installed: Long? = 4, offered: Long = 5) = AppRow(
        app = testApp(versionCode = offered, packageName = "org.example.app"),
        installed = installed?.let { InstalledVersion(it, null) },
        status = status
    )

    @Test
    fun downloadedShowsOnlyForUpdatesAndNewInstalls() {
        val saved = mapOf(record.repo to record)
        assertEquals(record, withStatus(AppStatus.UPDATE_AVAILABLE).downloaded(saved))
        assertEquals(record, withStatus(AppStatus.NOT_INSTALLED, installed = null).downloaded(saved))
        assertNull(withStatus(AppStatus.UP_TO_DATE, installed = 5).downloaded(saved))
        assertNull(withStatus(AppStatus.OTHER_SIGNER).downloaded(saved))
        // Another project's build as far as was known, tried from its details all the same:
        // the file passed the key check and was saved, and the row says so, as its status
        // stays what it was until the index tells the key.
        assertEquals(record, withStatus(AppStatus.OTHER_BUILD).downloaded(saved))
        assertNull(withStatus(AppStatus.UPDATE_AVAILABLE).downloaded(emptyMap()))
    }

    @Test
    fun rowShowsDownloadAgainWhenOfferIsNewerThanSaved() {
        assertNull(withStatus(AppStatus.UPDATE_AVAILABLE, offered = 6).downloaded(mapOf(record.repo to record)))
    }

    @Test
    fun installsDirectlyOnlyWithRootGranted() {
        assertTrue(installsDirectly(RootUiState.On(null)))
        assertTrue(installsDirectly(RootUiState.On("Magisk 30.7")))
        for (state in listOf(RootUiState.Off, RootUiState.Checking, RootUiState.OnButRefused, RootUiState.Denied, RootUiState.Missing)) {
            assertFalse(state.toString(), installsDirectly(state))
        }
    }

    @Test
    fun downloadAllTakesOnlyTheRowsNotSavedYet() {
        val saved = mapOf(record.repo to record)
        val savedRow = withStatus(AppStatus.UPDATE_AVAILABLE)
        val other = withStatus(AppStatus.UPDATE_AVAILABLE).let { it.copy(app = it.app.copy(packageName = "org.other.app")) }
        assertEquals(listOf(other), toDownload(listOf(savedRow, other), saved, direct = false))
        // Through root every update is installed, whatever was saved before.
        assertEquals(listOf(savedRow, other), toDownload(listOf(savedRow, other), saved, direct = true))
    }

    @Test
    fun downloadedOnlyOnTheRowOfTheRepositoryItWasDownloadedFor() {
        val saved = mapOf(record.repo to record)
        val fork = withStatus(AppStatus.NOT_INSTALLED, installed = null).let { it.copy(app = it.app.copy(fullName = "fork/app")) }
        assertNull(fork.downloaded(saved))
    }

    @Test
    fun eachProjectsRowShowsItsOwnFileOfThePackage() {
        val mirrorRecord = record.copy(versionCode = 4, uri = "content://downloads/2", repo = "mirror/app")
        val saved = mapOf(record.repo to record, mirrorRecord.repo to mirrorRecord)
        val mirror = withStatus(AppStatus.UPDATE_AVAILABLE, installed = 3, offered = 4).let { it.copy(app = it.app.copy(fullName = "mirror/app")) }
        assertEquals(mirrorRecord, mirror.downloaded(saved))
        assertEquals(record, withStatus(AppStatus.UPDATE_AVAILABLE, installed = 3).downloaded(saved))
        // Download all does not take the row whose file is saved, whichever finished first.
        assertEquals(emptyList<AppRow>(), toDownload(listOf(mirror), saved, direct = false))
    }

    @Test
    fun rowOffersToRemoveOnlyTheAppItIsOf() {
        assertEquals("org.example.app", withStatus(AppStatus.UP_TO_DATE, installed = 5).removable)
        assertEquals("org.example.app", withStatus(AppStatus.OTHER_SIGNER).removable)
        // Another app altogether under the same package name: removing it is not this row's
        // to offer, least of all under this app's name.
        assertNull(withStatus(AppStatus.OTHER_APP).removable)
        assertNull(withStatus(AppStatus.NOT_INSTALLED, installed = null).removable)
        val unread = withStatus(AppStatus.UP_TO_DATE).let { it.copy(app = it.app.copy(packageName = null)) }
        assertNull(unread.removable)
    }

    @Test
    fun keyThatDoesNotMatchAnotherAppIsNotToldAsAReasonToRemoveIt() {
        assertEquals(R.string.fail_signature, failureText(FailReason.SIGNATURE_MISMATCH, AppStatus.OTHER_SIGNER))
        assertEquals(R.string.fail_signature, failureText(FailReason.SIGNATURE_MISMATCH, AppStatus.UPDATE_AVAILABLE))
        // "Uninstall it first" would be said of the other app.
        assertEquals(R.string.other_app_note, failureText(FailReason.SIGNATURE_MISMATCH, AppStatus.OTHER_APP))
        assertEquals(R.string.fail_download, failureText(FailReason.DOWNLOAD, AppStatus.OTHER_APP))
    }

    @Test
    fun installsDirectlyNotOnceRootWasRefused() {
        assertFalse(installsDirectly(RootUiState.On(null), lost = true))
        assertTrue(installsDirectly(RootUiState.On(null), lost = false))
    }
}
