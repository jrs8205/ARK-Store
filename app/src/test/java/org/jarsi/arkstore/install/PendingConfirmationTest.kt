package org.jarsi.arkstore.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun promptMayHoldAnything() {
        val odd = pending.copy(prompt = "#Intent;S.note=a\nb;end")
        assertEquals(odd, PendingConfirmation.decode("owner/app", odd.encode()))
    }

    @Test
    fun whatIsNotARecordIsNull() {
        assertNull(PendingConfirmation.decode("owner/app", null))
        assertNull(PendingConfirmation.decode("owner/app", ""))
        assertNull(PendingConfirmation.decode("owner/app", "x\norg.example\n\n#Intent;end"))
        assertNull(PendingConfirmation.decode("owner/app", "42\norg.example\nExample"))
        assertNull(PendingConfirmation.decode("owner/app", "42\n\nExample\n#Intent;end"))
        assertNull(PendingConfirmation.decode("owner/app", "42\norg.example\nExample\n"))
    }

    @Test
    fun onlyOldSessionsNobodyKnowsAreLeftovers() {
        val now = 1_000_000L
        val alive = mapOf(1 to now - 120_000, 2 to now - 120_000, 3 to now - 1_000)
        assertEquals(setOf(2), leftoverSessions(alive, setOf(1), now, 60_000))
        assertEquals(emptySet<Int>(), leftoverSessions(emptyMap(), emptySet(), now, 60_000))
    }
}
