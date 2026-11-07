package org.jarsi.arkstore.backup

/**
 * Keeps a restore with root off the apps that are installed though the store does not see
 * them: an app without a launcher icon is hidden from the store, and restoring it would put
 * the backup's files and data over the app's own without a word.
 */
internal object RootGuard {

    /**
     * [installed] and [unchecked] are left alone, by name: the first because root found them
     * installed, the second because it could not tell. [rest] may be restored.
     */
    data class Split(val installed: List<String>, val unchecked: List<String>, val rest: List<RestoreEntry>)

    /** [selected] apart, asking [isInstalled] of each app the store sees as not installed. */
    suspend fun split(selected: List<RestoreEntry>, isInstalled: suspend (String) -> Boolean?): Split {
        val installed = ArrayList<String>()
        val unchecked = ArrayList<String>()
        val rest = ArrayList<RestoreEntry>()
        for (row in selected) {
            val asked = row.status == RestoreStatus.NOT_INSTALLED && AppBackup.isPackageName(row.entry.packageName)
            when (if (asked) isInstalled(row.entry.packageName) else false) {
                false -> rest += row
                true -> installed += row.entry.label
                null -> unchecked += row.entry.label
            }
        }
        return Split(installed, unchecked, rest)
    }
}
