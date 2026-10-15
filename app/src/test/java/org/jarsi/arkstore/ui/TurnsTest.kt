package org.jarsi.arkstore.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnsTest {

    @Test
    fun onlyTheLatestTurnCounts() {
        val turns = Turns()
        val first = turns.next()
        assertTrue(turns.isLatest(first))
        val second = turns.next()
        assertFalse(turns.isLatest(first))
        assertTrue(turns.isLatest(second))
    }
}
