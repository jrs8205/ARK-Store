package org.jarsi.arkstore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun requirementTheStoreItselfMeetsIsNotWorthTelling() {
        // Every device that runs the store runs such a file; the manifest of a file that names
        // no lowest Android gives 1.
        assertFalse(AndroidVersions.worthTelling(minSdk = 1, ownMinSdk = 26))
        assertFalse(AndroidVersions.worthTelling(minSdk = 26, ownMinSdk = 26))
        assertTrue(AndroidVersions.worthTelling(minSdk = 27, ownMinSdk = 26))
    }

    @Test
    fun levelsNotYetNamedHaveNoName() {
        assertNull(AndroidVersions.name(0))
        assertNull(AndroidVersions.name(99))
    }
}
