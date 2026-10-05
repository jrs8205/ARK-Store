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

    fun contains(app: StoreApp): Boolean = key(app) in _keys.value

    /** Bookmarks [app], or takes the bookmark away when it has one. */
    fun toggle(app: StoreApp) {
        val key = key(app)
        val keys = if (key in _keys.value) _keys.value - key else _keys.value + key
        preferences.edit { putStringSet(PREF_KEYS, keys) }
        _keys.value = keys
    }

    companion object {
        private const val PREFS = "bookmarks"
        private const val PREF_KEYS = "keys"

        fun key(app: StoreApp): String = app.packageName ?: app.fullName
    }
}
