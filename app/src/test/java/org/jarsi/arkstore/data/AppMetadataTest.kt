package org.jarsi.arkstore.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMetadataTest {

    private val english = AppMetadata("https://x/en/full_description.txt", listOf("https://x/en/1.png", "https://x/en/2.png"))
    private val finnish = AppMetadata("https://x/fi/full_description.txt", emptyList())

    @Test
    fun metadataIsReadTheWayTheIndexWritesIt() {
        val written = JSONObject()
            .put(
                "en",
                JSONObject()
                    .put("description", "https://x/en/full_description.txt")
                    .put("screenshots", JSONArray(listOf("https://x/en/1.png", "https://x/en/2.png")))
            )
            .put("fi", JSONObject().put("screenshots", JSONArray(listOf("https://x/fi/1.png"))))
        val expected = mapOf("en" to english, "fi" to AppMetadata(null, listOf("https://x/fi/1.png")))
        assertEquals(expected, AppMetadata.mapOf(written))
    }

    @Test
    fun aDescriptionWrittenAsNullIsNone() {
        // Android's org.json reads a null as the string "null" unless asked whether it is null.
        val written = JSONObject().put(
            "fi",
            JSONObject().put("description", JSONObject.NULL).put("screenshots", JSONArray().put(JSONObject.NULL).put("https://x/fi/1.png"))
        )
        assertEquals(mapOf("fi" to AppMetadata(null, listOf("https://x/fi/1.png"))), AppMetadata.mapOf(written))
        assertEquals(mapOf("fi" to finnish), AppMetadata.mapOf(JSONObject().put("fi", finnish.toJson())))
    }

    @Test
    fun anAppWithoutMetadataHasNone() {
        assertTrue(AppMetadata.mapOf(null).isEmpty())
        assertTrue(AppMetadata.mapOf(JSONObject()).isEmpty())
        // A language with nothing to show is left out, as is one that is not an object.
        val odd = JSONObject().put("en", JSONObject().put("screenshots", JSONArray())).put("fi", "x")
        assertTrue(AppMetadata.mapOf(odd).isEmpty())
    }

    @Test
    fun theLanguageOfTheDeviceIsPickedFirst() {
        val both = mapOf("en" to english, "fi" to finnish)
        assertEquals(english, AppMetadata.pick(both, listOf("en", "fi")))
        assertEquals(english, AppMetadata.pick(both, listOf("sv", "en")))
        assertEquals(finnish.description, AppMetadata.pick(both, listOf("fi", "en"))!!.description)
    }

    @Test
    fun screenshotsOfAnotherLanguageStandInForOnesThatAreNotThere() {
        val both = mapOf("en" to english, "fi" to finnish)
        assertEquals(AppMetadata(finnish.description, english.screenshots), AppMetadata.pick(both, listOf("fi")))
        val pictured = mapOf("en" to AppMetadata(null, english.screenshots), "fi" to finnish)
        assertEquals(AppMetadata(finnish.description, english.screenshots), AppMetadata.pick(pictured, listOf("en")))
    }

    @Test
    fun summaryIsReadTheWayTheIndexWritesIt() {
        val written = JSONObject()
            .put("en", JSONObject().put("summary", "Install apps from GitHub").put("screenshots", JSONArray()))
            .put("fi", JSONObject().put("summary", "Asenna sovelluksia GitHubista"))
            .put("sv", JSONObject().put("summary", JSONObject.NULL).put("screenshots", JSONArray()))
            .put("de", JSONObject().put("summary", " "))
        val expected = mapOf(
            "en" to AppMetadata(null, emptyList(), "Install apps from GitHub"),
            "fi" to AppMetadata(null, emptyList(), "Asenna sovelluksia GitHubista")
        )
        assertEquals(expected, AppMetadata.mapOf(written))
        assertEquals(expected, AppMetadata.mapOf(JSONObject().also { json -> expected.forEach { (language, it) -> json.put(language, it.toJson()) } }))
    }

    @Test
    fun summaryOfTheLanguageOfTheDeviceIsPickedFirstThenEnglishButNeverAnother() {
        val both = mapOf("en" to english.copy(summary = "English"), "fi" to finnish.copy(summary = "Suomi"))
        assertEquals("Suomi", AppMetadata.pick(both, listOf("fi", "en"))!!.summary)
        assertEquals("English", AppMetadata.pick(both, listOf("sv"))!!.summary)
        // A summary in a language the device does not read is no better than the description.
        assertNull(AppMetadata.pick(mapOf("fi" to finnish.copy(summary = "Suomi")), listOf("sv"))!!.summary)
        assertNull(AppMetadata.pick(mapOf("fi" to finnish.copy(summary = "Suomi")), listOf("en"))!!.summary)
        // A language without a summary does not hide the English one.
        val pictured = mapOf("fi" to finnish, "en" to english.copy(summary = "English"))
        assertEquals("English", AppMetadata.pick(pictured, listOf("fi"))!!.summary)
        assertNull(AppMetadata.pick(mapOf("en" to english), listOf("en"))!!.summary)
    }

    @Test
    fun englishStandsInForALanguageThatIsNotThere() {
        val both = mapOf("fi" to finnish, "en" to english)
        assertEquals(english, AppMetadata.pick(both, listOf("sv")))
        assertEquals(english, AppMetadata.pick(both, emptyList()))
        // Without English, whatever there is.
        assertEquals(finnish, AppMetadata.pick(mapOf("fi" to finnish), listOf("sv")))
        assertNull(AppMetadata.pick(emptyMap(), listOf("en")))
    }
}
