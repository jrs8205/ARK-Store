package org.jarsi.arkstore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidVersionsTest {

    @Test
    fun apiLevelsAreNamedAsAndroidVersions() {
        assertEquals("4.4", AndroidVersions.name(19))
        assertEquals("8.0", AndroidVersions.name(26))
        assertEquals("9", AndroidVersions.name(28))
        assertEquals("12L", AndroidVersions.name(32))
        assertEquals("16", AndroidVersions.name(36))
    }

    @Test
    fun levelsNotYetNamedHaveNoName() {
        assertNull(AndroidVersions.name(0))
        assertNull(AndroidVersions.name(99))
    }
}
