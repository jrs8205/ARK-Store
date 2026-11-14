package org.jarsi.arkstore.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The versions the user went back to: an earlier version of an app installed on purpose (see
 * [OlderVersions]), by package. The update to a newer version is offered and announced like
 * any other, but while the version gone back to is the one installed, it is not installed
 * unasked: that would undo what the user did. Once another version is installed, by the
 * user's hand here or anywhere else, the entry says nothing any more.
 */
object KeptVersions {
    private const val PREFS = "kept_versions"

    /** Whether [installedVersion] of [packageName] is the one the user went back to, by [kept]. */
    fun keeps(kept: Map<String, Long>, packageName: String?, installedVersion: Long): Boolean =
        packageName != null && kept[packageName] == installedVersion

    /** The version gone back to, by package. */
    fun read(context: Context): Map<String, Long> =
        preferences(context).all.mapNotNull { (packageName, version) -> (version as? Long)?.let { packageName to it } }.toMap()

    /** Notes that the user went back to [versionCode] of [packageName]; an earlier note of the package gives way. */
    fun keep(context: Context, packageName: String, versionCode: Long) =
        preferences(context).edit { putLong(packageName, versionCode) }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
