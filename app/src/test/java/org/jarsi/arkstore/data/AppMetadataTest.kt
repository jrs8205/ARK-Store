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
    fun englishStandsInForALanguageThatIsNotThere() {
        val both = mapOf("fi" to finnish, "en" to english)
        assertEquals(english, AppMetadata.pick(both, listOf("sv")))
        assertEquals(english, AppMetadata.pick(both, emptyList()))
        // Without English, whatever there is.
        assertEquals(finnish, AppMetadata.pick(mapOf("fi" to finnish), listOf("sv")))
        assertNull(AppMetadata.pick(emptyMap(), listOf("en")))
    }
}
