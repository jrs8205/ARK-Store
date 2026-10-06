package org.jarsi.arkstore.ui

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSettingsTest {

    // Preferences that tell their listeners of a change as Android's do, and nothing more.
    private class Preferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        fun put(key: String, value: Any?) {
            values[key] = value
            listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) }
        }

        override fun getString(key: String?, defValue: String?): String? = values[key] as String? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners += listener
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners -= listener
        }

        override fun getAll(): MutableMap<String, *> = throw UnsupportedOperationException()
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            throw UnsupportedOperationException()
        override fun getInt(key: String?, defValue: Int): Int = throw UnsupportedOperationException()
        override fun getLong(key: String?, defValue: Long): Long = throw UnsupportedOperationException()
        override fun getFloat(key: String?, defValue: Float): Float = throw UnsupportedOperationException()
        override fun contains(key: String?): Boolean = throw UnsupportedOperationException()
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
    }

    @Test
    fun theThemeFollowsTheSettingsWhileItListens() {
        val preferences = Preferences()
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
        val preferences = Preferences()
        val settings = ThemeSettings(preferences)
        preferences.values[PREF_PALETTE] = "midnight"
        preferences.values[PREF_BLACK] = true
        settings.listen()
        assertEquals(Palette.MIDNIGHT, settings.palette)
        assertTrue(settings.black)
    }

    @Test
    fun closingTakesTheListenerAway() {
        val preferences = Preferences()
        val settings = ThemeSettings(preferences)
        settings.listen()
        assertEquals(1, preferences.listeners.size)
        settings.close()
        assertTrue(preferences.listeners.isEmpty())
        preferences.put(PREF_PALETTE, "wine")
        assertEquals(Palette.ARK, settings.palette)
    }
}
