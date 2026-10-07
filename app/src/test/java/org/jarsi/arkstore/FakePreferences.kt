package org.jarsi.arkstore

import android.content.SharedPreferences

/** Preferences that tell their listeners of a change as Android's do, and nothing more. */
class FakePreferences : SharedPreferences {
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
