package org.jarsi.arkstore.backup

import org.jarsi.arkstore.data.CatalogRules
import org.jarsi.arkstore.data.testApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a restore without root takes each app's file from, and what it leaves out. */
class RestorePlanTest {

    private val key = "a".repeat(64)
    private val otherKey = "b".repeat(64)
    private val android = CatalogRules.Android(34)

    private fun entry(files: Int = 1, signers: List<String> = listOf(key)) = BackupEntry(
        packageName = "org.example.app",
        label = "Example",
        versionCode = 12,
        versionName = "1.2",
        installer = null,
        signers = signers,
        updatedAt = 1,
        minSdk = 26,
        files = listOf(BackupFile("base.apk", 100)) + (2..files).map { BackupFile("split_$it.apk", 10) }
    )

    private val offer = testApp(fullName = "owner/app", packageName = "org.example.app", versionCode = 14).copy(signer = key)

    @Test
    fun catalogueFirstThenTheBackupsSingleFile() {
        assertEquals(
            listOf(RestoreSource.CATALOGUE, RestoreSource.BACKUP),
            RestorePlan.sources(entry(), RestoreStatus.NOT_INSTALLED, offer)
        )
        assertEquals(listOf(RestoreSource.BACKUP), RestorePlan.sources(entry(), RestoreStatus.NOT_INSTALLED, null))
    }

    @Test
    fun splitWithoutOfferHasNoSource() {
        assertEquals(emptyList<RestoreSource>(), RestorePlan.sources(entry(files = 2), RestoreStatus.NOT_INSTALLED, null))
        assertEquals(LeftOut.SPLIT, RestorePlan.leftOutReason(entry(files = 2), RestoreStatus.NOT_INSTALLED, triedCatalogue = false))
        assertFalse(RestorePlan.selectableWithoutRoot(entry(files = 2), RestoreStatus.NOT_INSTALLED, null))
    }

    @Test
    fun splitWithRejectedOfferIsLeftOut() {
        assertEquals(listOf(RestoreSource.CATALOGUE), RestorePlan.sources(entry(files = 2), RestoreStatus.NOT_INSTALLED, offer))
        assertTrue(RestorePlan.selectableWithoutRoot(entry(files = 2), RestoreStatus.NOT_INSTALLED, offer))
        // The catalogue's file was tried and did not do: nothing else can stand in for a split app.
        assertEquals(LeftOut.FAILED, RestorePlan.leftOutReason(entry(files = 2), RestoreStatus.NOT_INSTALLED, triedCatalogue = true))
    }

    @Test
    fun missingFilesOrNewerAndroidAreSelectableOnlyWithAnOffer() {
        for (status in listOf(RestoreStatus.FILES_MISSING, RestoreStatus.NEEDS_NEWER_ANDROID)) {
            assertTrue(status.name, RestorePlan.selectableWithoutRoot(entry(), status, offer))
            assertEquals(listOf(RestoreSource.CATALOGUE), RestorePlan.sources(entry(), status, offer))
            assertFalse(status.name, RestorePlan.selectableWithoutRoot(entry(), status, null))
        }
        assertEquals(LeftOut.FILES_MISSING, RestorePlan.leftOutReason(entry(), RestoreStatus.FILES_MISSING, triedCatalogue = false))
        assertEquals(
            LeftOut.NEEDS_NEWER_ANDROID,
            RestorePlan.leftOutReason(entry(), RestoreStatus.NEEDS_NEWER_ANDROID, triedCatalogue = false)
        )
        // A newer version installed, or one signed otherwise, is not the backup's to replace.
        assertFalse(RestorePlan.selectableWithoutRoot(entry(), RestoreStatus.NEWER_INSTALLED, offer))
        assertFalse(RestorePlan.selectableWithoutRoot(entry(), RestoreStatus.OTHER_SIGNER, offer))
    }

    @Test
    fun offerSkipsOtherBuildsAndWhatDoesNotRun() {
        val other = testApp(fullName = "fork/app", packageName = "org.example.app", versionCode = 20).copy(signer = otherKey)
        assertEquals(offer, RestorePlan.offer(entry(), listOf(other, offer), android))
        // A version that does not run here is no offer.
        assertNull(RestorePlan.offer(entry(), listOf(offer.copy(minSdk = 35)), android))
        // Another package is no offer either.
        assertNull(RestorePlan.offer(entry(), listOf(testApp(packageName = "org.other")), android))
        // Signed with a key the backup does not have: the backup's own file is the one to use.
        assertNull(RestorePlan.offer(entry(), listOf(other), android))
    }

    @Test
    fun offerWithAnUnknownSignerIsTriedAndCheckedAfterTheDownload() {
        val unknown = offer.copy(signer = null)
        assertEquals(unknown, RestorePlan.offer(entry(), listOf(unknown), android))
        // A backup that names no signer takes the catalogue's word, as a first install would.
        assertEquals(offer, RestorePlan.offer(entry(signers = emptyList()), listOf(offer), android))
    }

    @Test
    fun prereleasesAreNotOfferedForARestore() {
        assertNull(RestorePlan.offer(entry(), listOf(offer.copy(prerelease = true)), android))
    }
}
