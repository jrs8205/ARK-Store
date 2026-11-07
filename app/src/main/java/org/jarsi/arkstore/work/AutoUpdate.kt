package org.jarsi.arkstore.work

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Whether the background check installs the updates it finds without asking, and when, as
 * the settings tell it. Only an app the store itself installed is updated unasked, and only
 * through root, the one way the store installs anything itself; the rest are announced.
 */
data class AutoUpdate(
    val enabled: Boolean,
    /** Not on mobile data or another metered network. */
    val unmeteredOnly: Boolean,
    val chargingOnly: Boolean
) {
    /**
     * Whether updates may be installed unasked right now: with [root] given or not, on a
     * network that is [unmetered] or not, and [charging] or not.
     */
    fun allows(root: Boolean, unmetered: Boolean, charging: Boolean): Boolean =
        enabled && root && (!unmeteredOnly || unmetered) && (!chargingOnly || charging)

    companion object {
        const val PREFS = "updates"
        const val PREF_ENABLED = "auto_install"
        const val PREF_UNMETERED = "auto_unmetered"
        const val PREF_CHARGING = "auto_charging"

        fun of(preferences: SharedPreferences) = AutoUpdate(
            enabled = preferences.getBoolean(PREF_ENABLED, false),
            unmeteredOnly = preferences.getBoolean(PREF_UNMETERED, true),
            chargingOnly = preferences.getBoolean(PREF_CHARGING, false)
        )

        fun read(context: Context): AutoUpdate = of(preferences(context))

        fun write(context: Context, settings: AutoUpdate) = preferences(context).edit {
            putBoolean(PREF_ENABLED, settings.enabled)
            putBoolean(PREF_UNMETERED, settings.unmeteredOnly)
            putBoolean(PREF_CHARGING, settings.chargingOnly)
        }

        private fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
