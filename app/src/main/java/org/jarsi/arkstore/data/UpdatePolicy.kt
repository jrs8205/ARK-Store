package org.jarsi.arkstore.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The updates the user has asked not to be offered: a version [skipped] for a package, until
 * a newer one comes, and the packages [held] back from updates altogether. Neither is
 * listed as an update, counted, announced, or installed unasked.
 */
data class UpdatePolicy(val skipped: Map<String, Long>, val held: Set<String>) {

    /** Whether [app]'s version, as an update of its package, is not to be offered. */
    fun skips(app: StoreApp): Boolean {
        val packageName = app.packageName ?: return false
        return packageName in held || skipped[packageName] == app.versionCode
    }

    fun skip(app: StoreApp): UpdatePolicy {
        val packageName = app.packageName ?: return this
        return copy(skipped = skipped + (packageName to app.versionCode))
    }

    fun hold(packageName: String, held: Boolean): UpdatePolicy =
        copy(held = if (held) this.held + packageName else this.held - packageName)

    /** With [packageName]'s updates offered again: neither skipped nor held. */
    fun offer(packageName: String): UpdatePolicy =
        copy(skipped = skipped - packageName, held = held - packageName)

    /** This policy joined with [other]'s, as when one is brought over from another device. */
    operator fun plus(other: UpdatePolicy): UpdatePolicy =
        UpdatePolicy(skipped + other.skipped, held + other.held)

    /** The skipped versions as the preferences keep them, "package@versionCode". */
    fun skippedEntries(): Set<String> = skipped.mapTo(LinkedHashSet()) { "${it.key}@${it.value}" }

    fun write(context: Context) = preferences(context).edit {
        putStringSet(PREF_SKIPPED, skippedEntries())
        putStringSet(PREF_HELD, held)
    }

    companion object {
        val NONE = UpdatePolicy(emptyMap(), emptySet())
        private const val PREFS = "update_policy"
        private const val PREF_SKIPPED = "skipped"
        private const val PREF_HELD = "held"

        /** The policy that [skipped] entries, as [skippedEntries] gives them, and [held] packages make. */
        fun of(skipped: Set<String>, held: Set<String>): UpdatePolicy {
            val versions = skipped.mapNotNull { entry ->
                val at = entry.lastIndexOf('@')
                if (at <= 0) return@mapNotNull null
                entry.substring(at + 1).toLongOrNull()?.let { entry.substring(0, at) to it }
            }.toMap()
            return UpdatePolicy(versions, held.toSet())
        }

        fun read(context: Context): UpdatePolicy = preferences(context).let {
            of(it.getStringSet(PREF_SKIPPED, null).orEmpty(), it.getStringSet(PREF_HELD, null).orEmpty())
        }

        private fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
