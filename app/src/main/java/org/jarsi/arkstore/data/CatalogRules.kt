package org.jarsi.arkstore.data

/** What the catalogue offers and shows, decided without any I/O so that it can be tested. */
internal object CatalogRules {

    /**
     * The version of an app to offer: its full release [stable], or the prerelease [beta] when
     * beta versions are wanted and it really is an upgrade of the full release.
     *
     * GitHub lists releases by the date of the tagged commit, so a prerelease at the top of
     * the list is not necessarily newer than the full release: it may come from an older
     * branch, be a nightly build tagged over and over, or be a separate app with its own
     * package name. Only the same package with a higher version code is an upgrade. When
     * either manifest could not be read there is nothing to compare, and the full release
     * is offered.
     */
    fun offered(stable: StoreApp?, beta: StoreApp?, includeBeta: Boolean): StoreApp? {
        if (!includeBeta || beta == null) return stable
        if (stable == null) return beta
        val upgrade = beta.packageName != null &&
            beta.packageName == stable.packageName &&
            beta.versionCode > stable.versionCode
        return if (upgrade) beta else stable
    }

    /**
     * [app] as the user is to see it, or null when it is hidden. An automatically found app is
     * hidden unless such apps are wanted. One that also comes from one of the user's [sources]
     * is theirs to see whatever the index says about how it was found, and is then no
     * different from any other app.
     */
    fun shown(app: StoreApp, sources: List<String>, includeAuto: Boolean): StoreApp? = when {
        !app.auto -> app
        sources.any { belongsTo(app.fullName, it) } -> app.copy(auto = false)
        includeAuto -> app
        else -> null
    }

    fun belongsTo(fullName: String, source: String): Boolean =
        if (SourceStore.isRepository(source)) {
            fullName.equals(source, ignoreCase = true)
        } else {
            fullName.substringBefore('/').equals(source, ignoreCase = true)
        }
}
