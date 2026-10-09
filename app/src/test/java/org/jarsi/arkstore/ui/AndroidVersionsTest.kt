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
    fun requirementTheStoreItselfMeetsIsStillTold() {
        // The store needs 26 itself; the row is told whenever the requirement is known, so
        // that every app reads the same way. A manifest that names no lowest Android gives 1.
        assertEquals(
            AndroidVersions.Requirement.Version("8.0"),
            AndroidVersions.requirement(minSdk = 26, codename = null)
        )
        assertEquals(
            AndroidVersions.Requirement.Version("1.0"),
            AndroidVersions.requirement(minSdk = 1, codename = null)
        )
        assertEquals(
            AndroidVersions.Requirement.Version("14"),
            AndroidVersions.requirement(minSdk = 34, codename = null)
        )
    }

    @Test
    fun requirementNotYetNamedIsToldByItsLevel() {
        assertEquals(
            AndroidVersions.Requirement.Level(99),
            AndroidVersions.requirement(minSdk = 99, codename = null)
        )
    }

    @Test
    fun previewIsToldByItsCodenameBeforeTheLevel() {
        assertEquals(
            AndroidVersions.Requirement.Preview("Baklava"),
            AndroidVersions.requirement(minSdk = 36, codename = "Baklava")
        )
    }

    @Test
    fun unknownRequirementIsNotTold() {
        assertNull(AndroidVersions.requirement(minSdk = null, codename = null))
    }

    @Test
    fun levelsNotYetNamedHaveNoName() {
        assertNull(AndroidVersions.name(0))
        assertNull(AndroidVersions.name(99))
    }
}
