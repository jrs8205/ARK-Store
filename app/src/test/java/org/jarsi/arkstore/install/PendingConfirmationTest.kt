package org.jarsi.arkstore.install

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingConfirmationTest {
    private val pending = PendingConfirmation(
        "owner/app", 42, "org.example.app", "Example",
        "#Intent;action=android.content.pm.action.CONFIRM_INSTALL;i.android.content.pm.extra.SESSION_ID=42;end"
    )

    @Test
    fun recordSurvivesBeingStored() {
        assertEquals(pending, PendingConfirmation.decode("owner/app", pending.encode()))
    }

    @Test
    fun recordWithoutALabelComesBackWithoutOne() {
        val unnamed = pending.copy(label = null)
        assertEquals(unnamed, PendingConfirmation.decode("owner/app", unnamed.encode()))
    }

    @Test
    fun labelMayHoldALineBreak() {
        // A backup carries the app's name as the developer wrote it, line breaks and all.
        val named = pending.copy(label = "Example\nBeta")
        assertEquals(named, PendingConfirmation.decode("owner/app", named.encode()))
    }

    @Test
    fun promptMayHoldAnything() {
        val odd = pending.copy(prompt = "#Intent;S.note=a\nb;end")
        assertEquals(odd, PendingConfirmation.decode("owner/app", odd.encode()))
    }

    @Test
    fun whatIsNotARecordIsNull() {
        assertNull(PendingConfirmation.decode("owner/app", null))
        assertNull(PendingConfirmation.decode("owner/app", ""))
        assertNull(PendingConfirmation.decode("owner/app", "not json"))
        // The line-by-line form of the development builds before the record became JSON.
        assertNull(PendingConfirmation.decode("owner/app", "42\norg.example\nExample\n#Intent;end"))
        assertNull(PendingConfirmation.decode("owner/app", """{"session":"x","package":"org.example","prompt":"#Intent;end"}"""))
        assertNull(PendingConfirmation.decode("owner/app", """{"package":"org.example","prompt":"#Intent;end"}"""))
        assertNull(PendingConfirmation.decode("owner/app", """{"session":42,"package":"org.example","label":"Example"}"""))
        assertNull(PendingConfirmation.decode("owner/app", """{"session":42,"package":"","prompt":"#Intent;end"}"""))
        assertNull(PendingConfirmation.decode("owner/app", """{"session":42,"package":"org.example","prompt":""}"""))
    }

    @Test
    fun onlyOldFilesNobodyKeepsAreLeftovers() {
        val folder = createTempDirectory("apk").toFile()
        try {
            val now = 10_000_000L
            val old = File(folder, "1-old.apk").apply { writeText("a"); setLastModified(now - 7_200_000) }
            val kept = File(folder, "2-kept.apk").apply { writeText("b"); setLastModified(now - 7_200_000) }
            val fresh = File(folder, "3-fresh.apk").apply { writeText("c"); setLastModified(now - 60_000) }
            assertEquals(listOf(old), leftoverFiles(folder, setOf(kept), now, 3_600_000))
            assertEquals(emptyList<File>(), leftoverFiles(File(folder, "none"), emptySet(), now, 3_600_000))
            assertTrue(fresh.exists())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun onlyOldSessionsNobodyKnowsAreLeftovers() {
        val now = 1_000_000L
        val alive = mapOf(1 to now - 120_000, 2 to now - 120_000, 3 to now - 1_000)
        assertEquals(setOf(2), leftoverSessions(alive, setOf(1), now, 60_000))
        assertEquals(emptySet<Int>(), leftoverSessions(emptyMap(), emptySet(), now, 60_000))
    }
}
