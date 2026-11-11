package org.jarsi.arkstore.install

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** How the folder is shown in the Files app, which before Android 10 takes no folder to open. */
class ApkFolderTest {

    @Test
    fun theFolderItselfIsOpenedFromAndroid10On() {
        val views = ApkFolder.folderViews(29, "org.jarsi.arkstore", "restore-2026-11-07")
        assertEquals(
            ApkFolder.FolderView(
                "android.intent.action.VIEW",
                "content://com.android.externalstorage.documents/document/primary%3ADownload%2FARK-Store%2Frestore-2026-11-07",
                "vnd.android.document/directory"
            ),
            views.first()
        )
        assertEquals("android.intent.action.VIEW_DOWNLOADS", views.last().action)
    }

    @Test
    fun theStorageIsOpenedOnAndroid9WhereFilesTakesNoFolder() {
        val views = ApkFolder.folderViews(28, "org.jarsi.arkstore", null)
        assertEquals(
            ApkFolder.FolderView(
                "android.intent.action.VIEW",
                "content://com.android.externalstorage.documents/root/primary",
                "vnd.android.document/root",
                showAdvanced = true
            ),
            views.first()
        )
        assertEquals("android.intent.action.VIEW_DOWNLOADS", views.last().action)
    }

    @Test
    fun thePathIsTheOneTheFilesAppShows() {
        assertEquals("Download/ARK-Store", ApkFolder.pathOf(29, "org.jarsi.arkstore", null))
        assertEquals("Download/ARK-Store/restore-2026-11-07", ApkFolder.pathOf(35, "org.jarsi.arkstore", "restore-2026-11-07"))
        assertEquals("Android/data/org.jarsi.arkstore/files/Download/ARK-Store", ApkFolder.pathOf(28, "org.jarsi.arkstore", null))
    }

    @Test
    fun aFileMovedOutOfTheFolderIsNoLongerTheStores() {
        assertTrue(ApkFolder.stillSaved(false, { false }, { "Download/ARK-Store/" }))
        assertTrue(ApkFolder.stillSaved(false, { false }, { "Download/ARK-Store/restore-2026-11-07/" }))
        assertFalse(ApkFolder.stillSaved(false, { false }, { "Download/Shared/" }))
        // Gone from MediaStore.
        assertFalse(ApkFolder.stillSaved(false, { true }, { "" }))
        // MediaStore not to be asked just now says nothing of the file: the record stays, and
        // is looked at again the next time. Dropped, it would leave the file in the folder
        // for good, with nothing to take it away when a newer one comes.
        assertTrue(ApkFolder.stillSaved(false, { false }, { null }))
        assertTrue(ApkFolder.stillSaved(true, { true }, { null }))
        assertFalse(ApkFolder.stillSaved(true, { false }, { "Download/ARK-Store/" }))
    }

    @Test
    fun aFileIsUnderItsNameOnlyWhenItIsWhole() {
        val dir = Files.createTempDirectory("apkfolder").toFile()
        try {
            val source = File(dir, "download").apply { writeText("the whole file") }
            val target = ApkFolder.reserve(dir, "Example-1.0.apk")
            ApkFolder.copyWhole(source, target)
            assertEquals("the whole file", target.readText())
            assertEquals(listOf("Example-1.0.apk", "download"), dir.list()!!.sorted())
            // A copy that fails leaves neither half a file nor the empty one that held its name.
            val failing = ApkFolder.reserve(dir, "Example-2.0.apk")
            try {
                ApkFolder.copyWhole(File(dir, "missing"), failing)
                fail("A copy of nothing went through")
            } catch (_: IOException) {
            }
            assertEquals(listOf("Example-1.0.apk", "download"), dir.list()!!.sorted())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aNameTakenIsNotTakenAgain() {
        val dir = Files.createTempDirectory("apkfolder").toFile()
        try {
            assertEquals("Example-1.0.apk", ApkFolder.reserve(dir, "Example-1.0.apk").name)
            assertEquals("Example-1.0 (1).apk", ApkFolder.reserve(dir, "Example-1.0.apk").name)
            assertEquals("Example-1.0 (2).apk", ApkFolder.reserve(dir, "Example-1.0.apk").name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun savesAtOnceEachGetANameOfTheirOwn() {
        val dir = Files.createTempDirectory("apkfolder").toFile()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            val names = (1..threads).map {
                pool.submit<File> {
                    start.await()
                    ApkFolder.reserve(dir, "Example-1.0.apk")
                }
            }
            start.countDown()
            val files = names.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(threads, files.map { it.name }.toSet().size)
            assertTrue(files.all { it.isFile })
        } finally {
            pool.shutdownNow()
            dir.deleteRecursively()
        }
    }
}
