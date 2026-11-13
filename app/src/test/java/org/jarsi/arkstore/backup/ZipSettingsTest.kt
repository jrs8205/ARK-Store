package org.jarsi.arkstore.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipSettingsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dir() = File(temp.root, "backup-settings")

    @Test
    fun aReadKeepsTheSettingsInAFileOfItsOwn() {
        val settings = ZipSettings()
        val kept = settings.keep(dir(), settings.begin(100), "{\"a\":1}")
        assertEquals("{\"a\":1}", kept?.readText())
        assertEquals(listOf(kept), dir().listFiles()?.toList())
    }

    @Test
    fun aReadGivenUpLeavesTheSettingsOfTheReadBegunAfterIt() {
        val settings = ZipSettings()
        // The first read is given up for the second, but is still at its file when the second ends.
        val first = settings.begin(100)
        val second = settings.begin(100)
        val kept = settings.keep(dir(), second, "{\"new\":1}")!!
        settings.keep(dir(), first, "{\"old\":1}")
        assertTrue(kept.exists())
        assertEquals("{\"new\":1}", kept.readText())
    }

    @Test
    fun aReadGivenUpThatFoundNoSettingsLeavesThoseOfTheReadBegunAfterIt() {
        val settings = ZipSettings()
        val first = settings.begin(100)
        val second = settings.begin(100)
        val kept = settings.keep(dir(), second, "{\"new\":1}")!!
        assertNull(settings.keep(dir(), first, null))
        assertTrue(kept.exists())
    }

    @Test
    fun aReadRemovesWhatTheReadsBeforeItKept() {
        val settings = ZipSettings()
        val old = settings.keep(dir(), settings.begin(100), "{\"old\":1}")!!
        val kept = settings.keep(dir(), settings.begin(100), "{\"new\":1}")!!
        assertFalse(old.exists())
        assertTrue(kept.exists())
        // A zip without settings leaves none of an earlier read's to be taken for its own.
        assertNull(settings.keep(dir(), settings.begin(100), null))
        assertEquals(emptyList<File>(), dir().listFiles()?.toList())
    }

    @Test
    fun aReadRemovesWhatAnEarlierRunOfTheStoreLeft() {
        val earlier = ZipSettings()
        repeat(5) { earlier.begin(100) }
        val left = earlier.keep(dir(), earlier.begin(100), "{\"old\":1}")!!
        // The store starts again and counts its reads from the beginning.
        val settings = ZipSettings()
        val kept = settings.keep(dir(), settings.begin(2_000), "{\"new\":1}")!!
        assertFalse(left.exists())
        assertTrue(kept.exists())
    }

    @Test
    fun whatIsNoFileOfAReadGoesToo() {
        val settings = ZipSettings()
        dir().mkdirs()
        val stray = File(dir(), "settings.json").apply { writeText("{}") }
        settings.keep(dir(), settings.begin(100), "{\"a\":1}")
        assertFalse(stray.exists())
    }
}
