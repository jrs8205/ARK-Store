package org.jarsi.arkstore.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedApksTest {

    private fun saved(packageName: String = "org.example", versionCode: Long = 5, uri: String = "content://downloads/1") =
        SavedApk(packageName, versionCode, "1.$versionCode", uri, "owner/$packageName")

    @Test
    fun fileNameIsSafeAndShowsAppAndVersion() {
        assertEquals("Device-Watch-2.1.0.apk", SavedApks.fileName("Device Watch", "2.1.0", 21))
        assertEquals("A-B-C-7.apk", SavedApks.fileName("A/B:C*?", null, 7))
        assertEquals("app-3.apk", SavedApks.fileName("", "", 3))
        val foreign = SavedApks.fileName("Ääkköset ja 日本", "1.0", 1)
        assertTrue(foreign, foreign.matches(Regex("[A-Za-z0-9._-]+")))
        assertTrue(foreign, foreign.endsWith("-1.0.apk"))
        val long = SavedApks.fileName("x".repeat(300), "1.0", 1)
        assertTrue(long, long.length <= 100)
        assertTrue(long, long.endsWith("-1.0.apk"))
    }

    @Test
    fun fileNameNeverHidesTheFileOrClimbsOutOfTheFolder() {
        assertEquals("app-1.0.apk", SavedApks.fileName("..", "1.0", 1))
        assertEquals("Tool-1.0.apk", SavedApks.fileName(".Tool", "1.0", 1))
        assertEquals("Tool-1.0.apk", SavedApks.fileName("Tool", "../1.0", 1))
    }

    @Test
    fun recordsRoundTripThroughJson() {
        val records = mapOf("owner/a" to saved("a", 3, "content://x/1"), "owner/b" to saved("b", 9, "file:///y/b.apk"))
        assertEquals(records, SavedApks.parse(SavedApks.toJson(records)))
        // Two projects' files of one package are two records.
        val fork = saved("a", 4, "content://x/2").copy(repo = "fork/a")
        assertEquals(records + ("fork/a" to fork), SavedApks.parse(SavedApks.toJson(records + ("fork/a" to fork))))
        assertEquals(emptyMap<String, SavedApk>(), SavedApks.parse(null))
        assertEquals(emptyMap<String, SavedApk>(), SavedApks.parse(""))
        assertEquals(emptyMap<String, SavedApk>(), SavedApks.parse("{not json"))
    }

    @Test
    fun aRecordIsKeptAfterItsVersionIsInstalled() {
        // Kept so that the file is deleted when a newer one comes; the rows hide it once installed.
        val records = mapOf("org.example" to saved(versionCode = 5))
        assertEquals(records, SavedApks.kept(records) { true })
    }

    @Test
    fun pendingDropsRecordsWhoseFileIsGone() {
        val records = mapOf("org.example" to saved(versionCode = 5))
        assertEquals(emptyMap<String, SavedApk>(), SavedApks.kept(records) { false })
    }

    @Test
    fun fileNameFallsBackToThePackageForATitleOfOtherScripts() {
        assertEquals("org.telegram-1.0.apk", SavedApks.fileName("Телеграм", "1.0", 1, "org.telegram"))
        assertEquals("app-1.0.apk", SavedApks.fileName("微信", "1.0", 1, null))
    }

    @Test
    fun onlyAFileStillInTheFolderIsTheStoresToDelete() {
        assertTrue(SavedApks.inFolder("Download/ARK-Store/"))
        assertTrue(SavedApks.inFolder("Download/ARK-Store/restore-2026-11-07/"))
        assertFalse(SavedApks.inFolder("Download/"))
        assertFalse(SavedApks.inFolder("Download/ARK-Store-old/"))
        assertFalse(SavedApks.inFolder("Documents/ARK-Store/"))
        assertFalse(SavedApks.inFolder(null))
    }

    @Test
    fun savedIsNotShownWhenTheCatalogueOffersNewer() {
        val record = saved(versionCode = 5)
        assertNull(SavedApks.shownFor(record, installedVersion = 4, offeredVersion = 6))
        assertEquals(record, SavedApks.shownFor(record, 4, 5))
        // The catalogue does not always know the version before the file is read.
        assertEquals(record, SavedApks.shownFor(record, 4, 0))
        assertEquals(record, SavedApks.shownFor(record, null, 5))
        assertNull(SavedApks.shownFor(record, 5, 5))
        assertNull(SavedApks.shownFor(null, 4, 5))
        // The release a file is of is written down with it.
        val tagged = mapOf("owner/a" to saved("a", 3, "content://x/1").copy(tag = "v1.3"))
        assertEquals(tagged, SavedApks.parse(SavedApks.toJson(tagged)))
    }

    @Test
    fun recordsOfEarlierVersionsAreReadByTheirProject() {
        // Up to 1.11.0 the records were kept by package.
        val json = """{"org.example":{"versionCode":5,"versionName":"1.5","uri":"content://x/1","repo":"owner/example"}}"""
        assertEquals(
            mapOf("owner/example" to SavedApk("org.example", 5, "1.5", "content://x/1", "owner/example")),
            SavedApks.parse(json)
        )
        // Nor does a record from before the releases were written down tell which its file is of.
        assertEquals("", SavedApks.parse(json).getValue("owner/example").tag)
    }

    @Test
    fun aSaveReplacesTheProjectsOwnRecordOnly() {
        val records = mapOf("owner/a" to saved("a", uri = "uri1"), "owner/b" to saved("b", uri = "uri2"))
        val newer = saved("a", versionCode = 6, uri = "uri3")
        assertEquals(SavedApks.Update(records + ("owner/a" to newer), listOf("uri1")), SavedApks.withSaved(records, newer))
        val same = saved("a", uri = "uri1")
        assertEquals(SavedApks.Update(records, emptyList()), SavedApks.withSaved(records, same))
        val other = saved("c", uri = "uri9")
        assertEquals(SavedApks.Update(records + ("owner/c" to other), emptyList()), SavedApks.withSaved(records, other))
    }

    @Test
    fun anotherProjectsOlderFileOfThePackageGoesWhenANewerComes() {
        val older = saved("a", versionCode = 5, uri = "uri5").copy(repo = "mirror/a")
        val records = mapOf("mirror/a" to older)
        val newer = saved("a", versionCode = 6, uri = "uri6")
        assertEquals(SavedApks.Update(mapOf("owner/a" to newer), listOf("uri5")), SavedApks.withSaved(records, newer))
    }

    @Test
    fun anOlderFileSavedLastNeitherReplacesNorCostsTheNewer() {
        // The newer one may be gone by now, removed by the user: the older one is kept all the same.
        val newer = saved("a", versionCode = 6, uri = "uri6")
        val records = mapOf("owner/a" to newer)
        val older = saved("a", versionCode = 5, uri = "uri5").copy(repo = "mirror/a")
        assertEquals(SavedApks.Update(records + ("mirror/a" to older), emptyList()), SavedApks.withSaved(records, older))
    }

    @Test
    fun restoreSubfolderIsNamedByTheDay() {
        assertEquals("restore-2026-11-07", ApkFolder.restoreSubfolder(java.time.LocalDate.of(2026, 11, 7)))
    }
}
