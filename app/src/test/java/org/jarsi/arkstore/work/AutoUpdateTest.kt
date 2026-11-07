package org.jarsi.arkstore.work

import org.jarsi.arkstore.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUpdateTest {

    @Test
    fun allowsOnlyWithRoot() {
        val off = AutoUpdate(enabled = false, unmeteredOnly = false, chargingOnly = false)
        assertFalse(off.allows(root = true, unmetered = true, charging = true))
        val on = off.copy(enabled = true)
        assertTrue(on.allows(root = true, unmetered = false, charging = false))
        // Without root the store installs nothing itself; the check only tells of updates.
        assertFalse(on.allows(root = false, unmetered = true, charging = true))
    }

    @Test
    fun theNetworkAndTheChargerAreWaitedForWhenAsked() {
        val wifi = AutoUpdate(enabled = true, unmeteredOnly = true, chargingOnly = false)
        assertTrue(wifi.allows(root = true, unmetered = true, charging = false))
        assertFalse(wifi.allows(root = true, unmetered = false, charging = true))
        val charger = AutoUpdate(enabled = true, unmeteredOnly = false, chargingOnly = true)
        assertTrue(charger.allows(root = true, unmetered = false, charging = true))
        assertFalse(charger.allows(root = true, unmetered = true, charging = false))
        val both = AutoUpdate(enabled = true, unmeteredOnly = true, chargingOnly = true)
        assertTrue(both.allows(root = true, unmetered = true, charging = true))
        assertFalse(both.allows(root = true, unmetered = true, charging = false))
    }

    @Test
    fun settingsAreOffByDefaultAndWaitForWifi() {
        val preferences = FakePreferences()
        assertEquals(AutoUpdate(enabled = false, unmeteredOnly = true, chargingOnly = false), AutoUpdate.of(preferences))
        preferences.values[AutoUpdate.PREF_ENABLED] = true
        preferences.values[AutoUpdate.PREF_UNMETERED] = false
        preferences.values[AutoUpdate.PREF_CHARGING] = true
        assertEquals(AutoUpdate(enabled = true, unmeteredOnly = false, chargingOnly = true), AutoUpdate.of(preferences))
    }
}
