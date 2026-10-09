package org.jarsi.arkstore.work

import org.jarsi.arkstore.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUpdateTest {

    @Test
    fun updatesAreInstalledUnaskedOnlyWhenAskedForAndFromAndroid12On() {
        val off = AutoUpdate(enabled = false, unmeteredOnly = false, chargingOnly = false)
        assertFalse(off.allows(sdk = 35, unmetered = true, charging = true))
        val on = off.copy(enabled = true)
        assertTrue(on.allows(sdk = 31, unmetered = false, charging = false))
        assertFalse(on.allows(sdk = 30, unmetered = true, charging = true))
    }

    @Test
    fun theNetworkAndTheChargerAreWaitedForWhenAsked() {
        val wifi = AutoUpdate(enabled = true, unmeteredOnly = true, chargingOnly = false)
        assertTrue(wifi.allows(sdk = 34, unmetered = true, charging = false))
        assertFalse(wifi.allows(sdk = 34, unmetered = false, charging = true))
        val charger = AutoUpdate(enabled = true, unmeteredOnly = false, chargingOnly = true)
        assertTrue(charger.allows(sdk = 34, unmetered = false, charging = true))
        assertFalse(charger.allows(sdk = 34, unmetered = true, charging = false))
        val both = AutoUpdate(enabled = true, unmeteredOnly = true, chargingOnly = true)
        assertTrue(both.allows(sdk = 34, unmetered = true, charging = true))
        assertFalse(both.allows(sdk = 34, unmetered = true, charging = false))
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
