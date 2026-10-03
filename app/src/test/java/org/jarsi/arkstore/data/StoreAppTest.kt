package org.jarsi.arkstore.data

import org.junit.Assert.assertEquals
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
}
