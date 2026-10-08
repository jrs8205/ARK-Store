package org.jarsi.arkstore.data

import org.jarsi.arkstore.work.AutoUpdate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BackupTest {

    private val backup = Backup(
        sources = listOf("jrs8205", "alice/app"),
        bookmarks = setOf("org.example", "bob/tool"),
        settings = Backup.Settings(
            includeBeta = true,
            includeAuto = false,
            catalogues = mapOf("izzy" to true, "fdroid" to false),
            palette = "wine",
            black = true,
            material = false,
            hideTop = true,
            sort = "NAME",
            autoUpdate = AutoUpdate(enabled = true, unmeteredOnly = false, chargingOnly = true)
        ),
        updates = UpdatePolicy.NONE.skip(testApp(packageName = "org.example", versionCode = 12)).hold("org.held", true)
    )

    @Test
    fun aFileWithoutUpdatePoliciesLeavesThemAlone() {
        val json = backup.toJson()
        json.remove("updates")
        assertEquals(UpdatePolicy.NONE, Backup.fromJson(json.toString())?.updates)
    }

    @Test
    fun aBackupSurvivesItsFile() {
        assertEquals(backup, Backup.fromJson(backup.toJson().toString()))
    }

    @Test
    fun theFileNamesTheStoreAndItsVersion() {
        val json = backup.toJson()
        assertEquals(Backup.APP, json.getString("app"))
        assertEquals(Backup.VERSION, json.getInt("version"))
        assertFalse(json.toString().contains("token"))
    }

    @Test
    fun whatIsNotABackupOfTheStoreIsRefused() {
        assertNull(Backup.fromJson("not json"))
        assertNull(Backup.fromJson("{}"))
        assertNull(Backup.fromJson(backup.toJson().put("app", "org.other").toString()))
        assertNull(Backup.fromJson(backup.toJson().put("version", Backup.VERSION + 1).toString()))
    }

    @Test
    fun aSettingLeftOutOfTheFileIsLeftAlone() {
        val json = backup.toJson()
        json.getJSONObject("settings").remove("palette")
        json.getJSONObject("settings").remove("autoUpdate")
        json.remove("bookmarks")
        val read = Backup.fromJson(json.toString())!!
        assertNull(read.settings.palette)
        assertNull(read.settings.autoUpdate)
        assertEquals(emptySet<String>(), read.bookmarks)
        assertEquals(backup.sources, read.sources)
        val minimal = Backup.fromJson(JSONObject().put("app", Backup.APP).put("version", 1).toString())!!
        assertEquals(Backup(emptyList(), emptySet(), Backup.Settings()), minimal)
    }

    @Test
    fun sourcesThatAreNoSourcesAreDropped() {
        val json = backup.toJson().put("sources", listOf("jrs8205", "not a source!", "", "Alice/app", "alice/app"))
        assertEquals(listOf("jrs8205", "Alice/app"), Backup.fromJson(json.toString())!!.sources)
    }

    @Test
    fun aFileIsReadForOnlySoManySourcesAndBookmarks() {
        val json = backup.toJson()
            .put("sources", List(Backup.MAX_SOURCES + 500) { "owner$it" })
            .put("bookmarks", List(Backup.MAX_BOOKMARKS + 500) { "org.example.app$it" })
        val read = Backup.fromJson(json.toString())!!
        assertEquals(Backup.MAX_SOURCES, read.sources.size)
        assertEquals("owner0", read.sources.first())
        assertEquals(Backup.MAX_BOOKMARKS, read.bookmarks.size)
    }

    @Test
    fun importedSourcesJoinTheCurrentOnesWithoutDoubling() {
        assertEquals(
            listOf("jrs8205", "alice/app", "bob/tool"),
            Backup.mergedSources(listOf("jrs8205", "alice/app"), listOf("JRS8205", "bob/tool", "Alice/app"))
        )
    }
}
