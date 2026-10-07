package org.jarsi.arkstore.ui

import org.jarsi.arkstore.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSettingsTest {

    @Test
    fun theThemeFollowsTheSettingsWhileItListens() {
        val preferences = FakePreferences()
        val settings = ThemeSettings(preferences)
        assertEquals(Palette.ARK, settings.palette)
        assertFalse(settings.black)
        settings.listen()
        preferences.put(PREF_PALETTE, "wine")
        assertEquals(Palette.WINE, settings.palette)
        preferences.put(PREF_BLACK, true)
        assertTrue(settings.black)
        preferences.put(PREF_PALETTE, "forest")
        assertEquals(Palette.FOREST, settings.palette)
    }

    @Test
    fun listeningCatchesUpWithWhatChangedBeforeIt() {
        val preferences = FakePreferences()
        val settings = ThemeSettings(preferences)
        preferences.values[PREF_PALETTE] = "midnight"
        preferences.values[PREF_BLACK] = true
        settings.listen()
        assertEquals(Palette.MIDNIGHT, settings.palette)
        assertTrue(settings.black)
    }

    @Test
    fun closingTakesTheListenerAway() {
        val preferences = FakePreferences()
        val settings = ThemeSettings(preferences)
        settings.listen()
        assertEquals(1, preferences.listeners.size)
        settings.close()
        assertTrue(preferences.listeners.isEmpty())
        preferences.put(PREF_PALETTE, "wine")
        assertEquals(Palette.ARK, settings.palette)
    }
}
