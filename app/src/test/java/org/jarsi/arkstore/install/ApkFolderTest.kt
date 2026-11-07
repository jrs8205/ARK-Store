package org.jarsi.arkstore.install

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
