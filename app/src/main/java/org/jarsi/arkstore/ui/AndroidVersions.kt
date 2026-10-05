package org.jarsi.arkstore.ui

/** The versions of Android by API level, for telling which Android an app needs. */
object AndroidVersions {
    // Index 0 is API level 1.
    private val names = arrayOf(
        "1.0", "1.1", "1.5", "1.6", "2.0", "2.0.1", "2.1", "2.2", "2.3", "2.3.3",
        "3.0", "3.1", "3.2", "4.0", "4.0.3", "4.1", "4.2", "4.3", "4.4", "4.4W",
        "5.0", "5.1", "6.0", "7.0", "7.1", "8.0", "8.1", "9", "10", "11",
        "12", "12L", "13", "14", "15", "16"
    )

    /** The version released as API level [sdk], such as "8.0" for 26, or null for a level not named here. */
    fun name(sdk: Int): String? = names.getOrNull(sdk - 1)

    /**
     * Whether an app that needs API level [minSdk] is worth telling about in a store that
     * itself needs [ownMinSdk]: every device that runs the store runs a file that needs no
     * more, so only a higher requirement says anything.
     */
    fun worthTelling(minSdk: Int, ownMinSdk: Int): Boolean = minSdk > ownMinSdk
}
