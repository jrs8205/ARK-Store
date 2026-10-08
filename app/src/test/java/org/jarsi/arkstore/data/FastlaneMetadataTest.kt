package org.jarsi.arkstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastlaneMetadataTest {

    private val folder = "fastlane/metadata/android/"
    private val texts = mapOf(
        "raw:${folder}en-US/short_description.txt" to "Chat for everyone\nand more",
        "raw:${folder}fi/short_description.txt" to "  Juttelua kaikille  ",
        "raw:${folder}de/short_description.txt" to "Chat für alle"
    )
    private val paths = listOf(
        "README.md",
        "${folder}en-US/short_description.txt",
        "${folder}en-US/full_description.txt",
        "${folder}en-US/images/phoneScreenshots/10.png",
        "${folder}en-US/images/phoneScreenshots/2.png",
        "${folder}en-US/images/phoneScreenshots/notes.txt",
        "${folder}en-US/images/icon.png",
        "${folder}fi/short_description.txt",
        "${folder}de/short_description.txt",
        "${folder}de/images/phoneScreenshots/1.webp"
    )
    private val read = ArrayList<String>()

    private fun metadata(paths: List<String>, languages: List<String>, texts: Map<String, String?> = this.texts) =
        FastlaneMetadata.read(paths, languages, address = { "raw:$it" }) { address ->
            read += address
            texts[address]
        }

    @Test
    fun readsTheFoldersOfTheDeviceLanguagesAndEnglishOnly() {
        val metadata = metadata(paths, listOf("fi"))
        assertEquals(
            mapOf(
                "fi" to AppMetadata(null, emptyList(), "Juttelua kaikille"),
                "en" to AppMetadata(
                    "raw:${folder}en-US/full_description.txt",
                    listOf("raw:${folder}en-US/images/phoneScreenshots/2.png", "raw:${folder}en-US/images/phoneScreenshots/10.png"),
                    "Chat for everyone and more"
                )
            ),
            metadata
        )
        // German is not read: a request for every language would be as many requests.
        assertEquals(listOf("raw:${folder}fi/short_description.txt", "raw:${folder}en-US/short_description.txt"), read)
    }

    @Test
    fun theUsualRegionComesFirstThenTheLanguageAloneThenOtherRegions() {
        val folders = listOf("en-GB", "en", "en-US", "en-AU").map { "$folder$it/full_description.txt" }
        assertEquals("raw:${folder}en-US/full_description.txt", metadata(folders, listOf("en"))["en"]?.description)
        assertEquals("raw:${folder}en/full_description.txt", metadata(folders.filter { "en-US" !in it }, listOf("en"))["en"]?.description)
        assertEquals("raw:${folder}en-AU/full_description.txt", metadata(folders.take(1) + folders.drop(3), listOf("en"))["en"]?.description)
    }

    @Test
    fun aFolderWithNothingToShowDoesNotHideTheNextOneOfItsLanguage() {
        val folders = listOf("${folder}en-US/short_description.txt", "${folder}en/full_description.txt")
        val metadata = metadata(folders, listOf("en"), mapOf("raw:${folder}en-US/short_description.txt" to ""))
        assertEquals(AppMetadata("raw:${folder}en/full_description.txt", emptyList()), metadata["en"])
    }

    @Test
    fun aSummaryThatCannotBeReadLeavesTheRestOfItsFolder() {
        val metadata = metadata(paths, listOf("en"), emptyMap())
        assertNull(metadata["en"]?.summary)
        assertEquals("raw:${folder}en-US/full_description.txt", metadata["en"]?.description)
    }

    @Test
    fun onlySoManyScreenshotsAndNothingWhenNoFolderHasAnything() {
        val many = (1..12).map { "${folder}en-US/images/phoneScreenshots/$it.png" }
        assertEquals(8, metadata(many, listOf("en"))["en"]?.screenshots?.size)
        assertEquals(emptyMap<String, AppMetadata>(), metadata(listOf("README.md", "${folder}de/images/icon.png"), listOf("en")))
    }

    @Test
    fun aSummaryIsOneLineOfAtMostTwoHundredCharacters() {
        val long = "x".repeat(300)
        val metadata = metadata(paths, listOf("en"), mapOf("raw:${folder}en-US/short_description.txt" to "\uFEFF$long"))
        assertEquals(200, metadata["en"]?.summary?.length)
    }
}
