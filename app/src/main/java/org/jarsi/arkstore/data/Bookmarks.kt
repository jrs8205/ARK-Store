package org.jarsi.arkstore.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The apps the user has bookmarked, kept on this device. A bookmark follows the app rather
 * than the place it was seen at: it is keyed by the package name, so that the same app
 * offered from another place later, or in another version, is still bookmarked. An app
 * whose package is not known is keyed by its full name instead.
 */
class Bookmarks(context: Context) {

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _keys = MutableStateFlow(preferences.getStringSet(PREF_KEYS, null).orEmpty().toSet())

    /** The keys of the bookmarked apps; see [key]. */
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    fun contains(app: StoreApp): Boolean = marked(app, _keys.value)

    /** Bookmarks [app], or takes the bookmark away when it has one. */
    fun toggle(app: StoreApp) {
        val keys = toggled(_keys.value, app)
        preferences.edit { putStringSet(PREF_KEYS, keys) }
        _keys.value = keys
    }

    companion object {
        private const val PREFS = "bookmarks"
        private const val PREF_KEYS = "keys"

        /**
         * The keys an app answers to: its package, and its full name, under which it was
         * bookmarked if its package was not known at the time. A bookmark is found by either.
         */
        fun keysOf(app: StoreApp): List<String> = listOfNotNull(app.packageName, app.fullName)

        fun marked(app: StoreApp, keys: Set<String>): Boolean = keysOf(app).any { it in keys }

        /**
         * [keys] with [app] bookmarked, or with its bookmark taken away when it has one. A
         * bookmark kept under the full name moves to the package once that is known.
         */
        fun toggled(keys: Set<String>, app: StoreApp): Set<String> {
            val own = keysOf(app)
            return if (own.any { it in keys }) keys - own.toSet() else keys + own.first()
        }
    }
}
