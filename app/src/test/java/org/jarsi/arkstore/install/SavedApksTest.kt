package org.jarsi.arkstore.install

import org.junit.Assert.assertEquals
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
        val records = mapOf("a" to saved("a", 3, "content://x/1"), "b" to saved("b", 9, "file:///y/b.apk"))
        assertEquals(records, SavedApks.parse(SavedApks.toJson(records)))
        assertEquals(emptyMap<String, SavedApk>(), SavedApks.parse(null))
        assertEquals(emptyMap<String, SavedApk>(), SavedApks.parse(""))
        assertEquals(emptyMap<String, SavedApk>(), SavedApks.parse("{not json"))
    }

    @Test
    fun pendingDropsInstalledVersions() {
        val records = mapOf("org.example" to saved(versionCode = 5))
        assertEquals(emptyMap<String, SavedApk>(), SavedApks.pending(records, { 5L }, { true }))
        assertEquals(emptyMap<String, SavedApk>(), SavedApks.pending(records, { 6L }, { true }))
        assertEquals(records, SavedApks.pending(records, { 4L }, { true }))
        assertEquals(records, SavedApks.pending(records, { null }, { true }))
    }

    @Test
    fun pendingDropsRecordsWhoseFileIsGone() {
        val records = mapOf("org.example" to saved(versionCode = 5))
        assertEquals(emptyMap<String, SavedApk>(), SavedApks.pending(records, { null }, { false }))
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
    }

    @Test
    fun olderToDeleteTouchesOnlyRecordedFilesOfThatPackage() {
        val records = mapOf("a" to saved("a", uri = "uri1"), "b" to saved("b", uri = "uri2"))
        assertEquals(listOf("uri1"), SavedApks.olderToDelete(records, "a", "uri3"))
        assertEquals(emptyList<String>(), SavedApks.olderToDelete(records, "a", "uri1"))
        assertEquals(emptyList<String>(), SavedApks.olderToDelete(records, "c", "uri9"))
    }

    @Test
    fun restoreSubfolderIsNamedByTheDay() {
        assertEquals("restore-2026-11-07", ApkFolder.restoreSubfolder(java.time.LocalDate.of(2026, 11, 7)))
    }
}
