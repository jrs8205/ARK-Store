package org.jarsi.arkstore.work

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit

/**
 * Whether the background check installs the updates it finds without asking, and when, as
 * the settings tell it. Only an app the store itself installed can be updated unasked, and
 * only from Android 12 on; the rest are announced as before.
 */
data class AutoUpdate(
    val enabled: Boolean,
    /** Not on mobile data or another metered network. */
    val unmeteredOnly: Boolean,
    val chargingOnly: Boolean
) {
    /**
     * Whether updates may be installed unasked right now: on an Android of [sdk], on a network
     * that is [unmetered] or not, and [charging] or not.
     */
    fun allows(sdk: Int, unmetered: Boolean, charging: Boolean): Boolean =
        enabled && sdk >= SUPPORTED_SDK && (!unmeteredOnly || unmetered) && (!chargingOnly || charging)

    companion object {
        /** Android 12, the first where an installer may ask not to have an update confirmed. */
        const val SUPPORTED_SDK = Build.VERSION_CODES.S
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
