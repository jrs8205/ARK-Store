package org.jarsi.arkstore.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackupTest {

    private val signer = "a".repeat(64)

    private val entry = BackupEntry(
        packageName = "org.example.app",
        label = "Example",
        versionCode = 12,
        versionName = "1.2",
        installer = "org.fdroid.fdroid",
        signers = listOf(signer),
        updatedAt = 1_700_000_000_000,
        minSdk = 26,
        files = listOf(BackupFile("base.apk", 123_456), BackupFile("split_config.arm64_v8a.apk", 2_345))
    )

    private fun json(vararg changes: Pair<String, Any?>): JSONObject {
        val json = entry.toJson()
        for ((key, value) in changes) json.put(key, value ?: JSONObject.NULL)
        return json
    }

    @Test
    fun entryRoundTrips() {
        assertEquals(entry, AppBackup.entryFromText(entry.toJson().toString()))
        val bare = entry.copy(versionName = null, installer = null, signers = emptyList(), minSdk = null)
        assertEquals(bare, AppBackup.entryFromJson(bare.toJson()))
    }

    @Test
    fun sizeSumsFiles() {
        assertEquals(125_801L, entry.size)
    }

    @Test
    fun rejectsPathCharacters() {
        assertNull(AppBackup.entryFromJson(json("package" to "../../etc")))
        assertNull(AppBackup.entryFromJson(json("package" to "org.example.app/..")))
        assertNull(AppBackup.entryFromJson(json("package" to "")))
        assertNull(AppBackup.entryFromJson(json("package" to "a".repeat(256) + ".b")))
        assertNull(AppBackup.entryFromJson(json("package" to "noDots")))
        assertNull(AppBackup.entryFromJson(json("package" to "1org.example")))
        for (name in listOf("..\\x.apk", "/x.apk", "x.apk/..", "../base.apk", ".hidden.apk", "x.APK", "x.zip", "")) {
            assertNull(name, AppBackup.entryFromJson(json("files" to files("base.apk" to 1L, name to 2L))))
        }
        assertFalse(AppBackup.isFileName("a".repeat(252) + ".apk"))
        assertTrue(AppBackup.isFileName("a".repeat(251) + ".apk"))
    }

    @Test
    fun rejectsWrongTypes() {
        assertNull(AppBackup.entryFromJson(json("versionCode" to "12")))
        assertNull(AppBackup.entryFromJson(json("versionCode" to -1)))
        assertNull(AppBackup.entryFromJson(json("updatedAt" to "yesterday")))
        assertNull(AppBackup.entryFromJson(json("label" to null)))
        assertNull(AppBackup.entryFromJson(json("label" to "x".repeat(256))))
        assertNull(AppBackup.entryFromJson(json("files" to null)))
        assertNull(AppBackup.entryFromJson(json("files" to JSONArray())))
        // The base file comes first, and every file once.
        assertNull(AppBackup.entryFromJson(json("files" to files("split.apk" to 1L, "base.apk" to 2L))))
        assertNull(AppBackup.entryFromJson(json("files" to files("base.apk" to 1L, "base.apk" to 2L))))
        assertNull(AppBackup.entryFromJson(json("files" to files("base.apk" to -1L))))
        assertNull(AppBackup.entryFromJson(json("signers" to JSONArray(listOf("not a digest")))))
        assertNull(AppBackup.entryFromJson(json("signers" to JSONArray(listOf(signer.uppercase())))))
        assertNull(AppBackup.entryFromJson(json("minSdk" to "26")))
        assertNull(AppBackup.entryFromText("not json"))
        assertNull(AppBackup.entryFromText("[]"))
    }

    @Test
    fun capsLists() {
        val many = files(*Array(AppBackup.MAX_FILES) { ("f$it.apk" to 1L) }.let { arrayOf("base.apk" to 1L) + it })
        assertNull(AppBackup.entryFromJson(json("files" to many)))
        val signers = JSONArray(List(AppBackup.MAX_SIGNERS + 1) { signer })
        assertNull(AppBackup.entryFromJson(json("signers" to signers)))
    }

    @Test
    fun indexRoundTripsSkippingBadEntriesAndDuplicates() {
        val other = entry.copy(packageName = "org.other.app", label = "Other")
        val again = entry.copy(label = "Later copy of the same package")
        val bad = entry.toJson().put("package", "../x")
        val index = AppBackup.indexToJson(listOf(entry, other), "2026-10-11T07:00:00Z")
        index.getJSONArray("apps").put(bad).put(again.toJson())
        assertEquals(listOf(entry, other), AppBackup.indexFromText(index.toString()))
        assertEquals("2026-10-11T07:00:00Z", index.getString("written"))
    }

    @Test
    fun indexRejectsOtherAppOrNewerVersion() {
        val index = AppBackup.indexToJson(listOf(entry), "2026-10-11T07:00:00Z")
        assertNotNull(AppBackup.indexFromText(index.toString()))
        assertNull(AppBackup.indexFromText(JSONObject(index.toString()).put("app", "com.other").toString()))
        assertNull(AppBackup.indexFromText(JSONObject(index.toString()).put("version", AppBackup.VERSION + 1).toString()))
        assertNull(AppBackup.indexFromText("{}"))
        assertNull(AppBackup.indexFromText("nonsense"))
        assertEquals(emptyList<BackupEntry>(), AppBackup.indexFromText(JSONObject(index.toString()).put("apps", JSONArray()).toString()))
    }

    @Test
    fun indexCapsEntries() {
        val index = AppBackup.indexToJson(emptyList(), "2026-10-11T07:00:00Z")
        val apps = index.getJSONArray("apps")
        repeat(AppBackup.MAX_ENTRIES + 1) { apps.put(entry.copy(packageName = "org.example.app$it").toJson()) }
        assertEquals(AppBackup.MAX_ENTRIES, AppBackup.indexFromText(index.toString())?.size)
    }

    @Test
    fun fileNameForFallsBack() {
        assertEquals("split_config.arm64_v8a.apk", AppBackup.fileNameFor("split_config.arm64_v8a.apk", 1))
        assertEquals("split_1.apk", AppBackup.fileNameFor("weird name.apk", 1))
        assertEquals("split_2.apk", AppBackup.fileNameFor("base.apk", 2))
        assertEquals("split_3.apk", AppBackup.fileNameFor("", 3))
    }

    @Test
    fun upToDateNeedsSameVersionTimeAndFiles() {
        val present = mapOf("base.apk" to 123_456L, "split_config.arm64_v8a.apk" to 2_345L, "app.json" to 300L)
        assertTrue(AppBackup.isUpToDate(entry, present, entry))
        assertFalse(AppBackup.isUpToDate(entry.copy(versionCode = 11), present, entry))
        assertFalse(AppBackup.isUpToDate(entry.copy(updatedAt = 1), present, entry))
        assertFalse(AppBackup.isUpToDate(entry.copy(files = entry.files.take(1)), present, entry))
        assertFalse(AppBackup.isUpToDate(entry, present + ("base.apk" to 1L), entry))
        // The label and the installer may change without the files changing.
        assertTrue(AppBackup.isUpToDate(entry.copy(label = "Renamed", installer = null), present, entry))
    }

    @Test
    fun partialFolderIsNotUpToDate() {
        assertFalse(AppBackup.isUpToDate(null, mapOf("base.apk" to 123_456L), entry))
        assertFalse(AppBackup.isUpToDate(entry, mapOf("split_config.arm64_v8a.apk" to 2_345L), entry))
        assertFalse(AppBackup.isUpToDate(entry, emptyMap(), entry))
    }

    @Test
    fun packageNames() {
        assertTrue(AppBackup.isPackageName("org.jarsi.arkstore"))
        assertTrue(AppBackup.isPackageName("a.b_c.D9"))
        assertFalse(AppBackup.isPackageName("org"))
        assertFalse(AppBackup.isPackageName("org..x"))
        assertFalse(AppBackup.isPackageName("org.9x"))
        assertFalse(AppBackup.isPackageName("org.x-y"))
        assertFalse(AppBackup.isPackageName("org/x.y"))
    }

    private fun files(vararg entries: Pair<String, Long>): JSONArray =
        JSONArray(entries.map { JSONObject().put("name", it.first).put("size", it.second) })
}
