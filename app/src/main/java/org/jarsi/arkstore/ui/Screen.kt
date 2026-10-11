package org.jarsi.arkstore.ui

/** The pages of the store: the list itself, and those opened from it, each a page of its own. */
internal enum class Screen {
    STORE,
    SETTINGS,
    BACKUP,
    RESTORE;

    /** Where going back from this page leads: the pages of the backup open from the settings. */
    val back: Screen
        get() = when (this) {
            STORE, SETTINGS -> STORE
            BACKUP, RESTORE -> SETTINGS
        }
}
