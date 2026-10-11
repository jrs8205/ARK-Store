package org.jarsi.arkstore.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTest {

    @Test
    fun backLeadsTowardsTheStore() {
        assertEquals(Screen.SETTINGS, Screen.BACKUP.back)
        assertEquals(Screen.SETTINGS, Screen.RESTORE.back)
        assertEquals(Screen.STORE, Screen.SETTINGS.back)
        assertEquals(Screen.STORE, Screen.STORE.back)
    }
}
