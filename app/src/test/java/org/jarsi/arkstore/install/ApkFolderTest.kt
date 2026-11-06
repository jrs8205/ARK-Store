package org.jarsi.arkstore.install

import org.junit.Assert.assertEquals
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
}
