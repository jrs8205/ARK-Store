package org.jarsi.arkstore.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreAppTest {

    @Test
    fun appIsCalledWhatItCallsItself() {
        val app = testApp(fullName = "bitwarden/android").copy(label = "Bitwarden")
        assertEquals("Bitwarden", app.title)
    }

    @Test
    fun repositoryNameStandsInForAMissingLabel() {
        assertEquals("android", testApp(fullName = "bitwarden/android").title)
    }

    @Test
    fun onlyTheCurrentIndexReaderCanSupplyASigningKey() {
        val entry = JSONObject().put("signer", "AB".repeat(32))
        assertNull(StoreApp.indexedSigner(entry))
        for (reader in listOf(0, 1, 3)) {
            assertNull(StoreApp.indexedSigner(entry.put("signerReader", reader)))
        }
        assertEquals("ab".repeat(32), StoreApp.indexedSigner(entry.put("signerReader", 2)))
        assertNull(StoreApp.indexedSigner(entry.put("signer", JSONObject.NULL)))
    }

    @Test
    fun aStoredRepositorySignerAlsoRequiresTheCurrentReader() {
        for (source in listOf(StoreApp.SOURCE_GITHUB, StoreApp.SOURCE_CODEBERG, StoreApp.SOURCE_GITLAB)) {
            val app = testApp().copy(source = source, signer = "ab".repeat(32))
            val stored = app.toJson()
            assertEquals(app.signer, StoreApp.fromJson(stored).signer)
            stored.remove("signerReader")
            assertNull(StoreApp.fromJson(stored).signer)
            assertNull(StoreApp.fromJson(stored.put("signerReader", 1)).signer)
        }
    }

    @Test
    fun catalogueSigningKeysDoNotComeFromTheIndexReader() {
        for (source in listOf(StoreApp.SOURCE_FDROID, StoreApp.SOURCE_IZZY)) {
            val app = testApp().copy(source = source, signer = "ab".repeat(32))
            val stored = app.toJson()
            stored.remove("signerReader")
            assertEquals(app.signer, StoreApp.fromJson(stored).signer)
        }
    }

    @Test
    fun iconIsReadTheWayTheIndexWritesIt() {
        assertEquals(AppIcon("https://x/icons/a.png", null, null), AppIcon.of("https://x/icons/a.png"))
        val layers = JSONObject().put("foreground", "https://x/icons/fg.json").put("background", "#ff112233")
        assertEquals(AppIcon(null, "https://x/icons/fg.json", "#ff112233"), AppIcon.of(layers))
        assertEquals(AppIcon(null, "https://x/icons/fg.png", null), AppIcon.of(JSONObject().put("foreground", "https://x/icons/fg.png")))
        assertNull(AppIcon.of(null))
        assertNull(AppIcon.of(JSONObject.NULL))
        assertNull(AppIcon.of(""))
        assertNull(AppIcon.of(JSONObject().put("background", "#ff000000")))
    }

    @Test
    fun iconSurvivesTheCatalogueCache() {
        val icons = listOf(
            AppIcon("https://x/icons/a.png", null, null),
            AppIcon(null, "https://x/icons/fg.json", "#ff112233"),
            AppIcon(null, "https://x/icons/fg.png", null),
            null
        )
        for (icon in icons) {
            val app = testApp().copy(icon = icon)
            assertEquals(icon, StoreApp.fromJson(JSONObject(app.toJson().toString())).icon)
        }
    }
}
