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

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as Set<String>?)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as Int? ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as Float? ?: defValue
    override fun contains(key: String?): Boolean = key in values

    /** Writes as Android's editor does, each change told to the listeners when applied. */
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { changes[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { changes[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) values.clear()
            for ((key, value) in changes) {
                if (value == null) values.remove(key) else values[key] = value
                listeners.toList().forEach { it.onSharedPreferenceChanged(this@FakePreferences, key) }
            }
        }
    }
}
