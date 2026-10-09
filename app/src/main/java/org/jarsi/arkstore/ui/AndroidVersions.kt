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

    /** The Android an app needs, as far as it is known. */
    sealed interface Requirement {
        /** A released Android, by its version such as "8.0". */
        data class Version(val name: String) : Requirement

        /** An API level the versions here do not name yet. */
        data class Level(val sdk: Int) : Requirement

        /** A preview of Android, by its codename. */
        data class Preview(val codename: String) : Requirement
    }

    /**
     * What to tell about an app whose file needs API level [minSdk], or the preview
     * [codename]: told whenever it is known, also for a requirement the store itself meets,
     * so that every app reads the same way; null when neither is known.
     */
    fun requirement(minSdk: Int?, codename: String?): Requirement? = when {
        codename != null -> Requirement.Preview(codename)
        minSdk == null -> null
        else -> name(minSdk)?.let { Requirement.Version(it) } ?: Requirement.Level(minSdk)
    }
}
