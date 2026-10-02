package org.jarsi.arkstore.data

import java.security.MessageDigest

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
        return if (upgrades(beta, stable)) beta else stable
    }

    /** Whether [beta] is the same package as [stable] with a higher version code. */
    fun upgrades(beta: StoreApp, stable: StoreApp): Boolean =
        beta.packageName != null &&
            beta.packageName == stable.packageName &&
            beta.versionCode > stable.versionCode

    /**
     * [app] as the user is to see it, or null when it is hidden. An automatically found app is
     * hidden unless such apps are wanted. One that also comes from one of the user's [sources]
     * is theirs to see whatever the index says about how it was found, and is then no
     * different from any other app.
     */
    fun shown(
        app: StoreApp,
        sources: List<String>,
        includeAuto: Boolean,
        catalogues: Set<String> = emptySet()
    ): StoreApp? = when {
        !app.fromRepository -> app.takeIf { it.source in catalogues }
        !app.auto -> app
        // Found by searching somewhere else than GitHub, which has a switch of its own.
        app.source != StoreApp.SOURCE_GITHUB -> app.takeIf { it.source in catalogues }
        sources.any { belongsTo(app.fullName, it) } -> app.copy(auto = false)
        includeAuto -> app
        else -> null
    }

    /** One file a catalogue offers of an app, as far as choosing between them goes. */
    data class CatalogueApk(
        val versionCode: Long,
        val abis: List<String>,
        val minSdk: Int,
        /** SHA-256 of the certificate the file is signed with, when the catalogue tells it. */
        val signer: String? = null
    )

    /**
     * The index of the file in [apks] to offer a device that runs the CPU architectures
     * [deviceAbis] and Android [sdk], or null when none suits it: the highest version among
     * those the device can run. A file that names no architecture runs on all of them.
     *
     * [installedSigners] are the certificates the app is installed with, or null when it is
     * not installed. Android updates an app only with a file signed like the installed one,
     * so such a file, or one whose signature is not known, goes before a newer one that is
     * signed otherwise.
     */
    fun pickCatalogueApk(
        apks: List<CatalogueApk>,
        deviceAbis: List<String>,
        sdk: Int,
        installedSigners: Set<String>? = null
    ): Int? {
        val runnable = apks.withIndex().filter { (_, apk) ->
            apk.minSdk <= sdk && (apk.abis.isEmpty() || apk.abis.any { it in deviceAbis })
        }
        val updating = installedSigners?.let { signers ->
            runnable.filter { (_, apk) -> apk.signer == null || apk.signer in signers }
        }
        return (updating?.takeIf { it.isNotEmpty() } ?: runnable)
            .maxByOrNull { (_, apk) -> apk.versionCode }
            ?.index
    }

    /**
     * The name of the file an app's download is kept in until it is installed. Every
     * [fullName] has a name of its own, whatever characters it is made of: two downloads
     * running at once must never write to the same file.
     */
    fun downloadName(fullName: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(fullName.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) } + ".apk"
    }

    /** One app of the list, with the other places that offer the same package. */
    data class Merged(val app: StoreApp, val alsoFrom: List<String>)

    /**
     * How far down the line a place is when the same app is offered from several: the
     * developer's own publication first, then their releases as found by searching, then the
     * catalogue that passes on the developer's files, and last the one that builds its own.
     * This is also the order in which new versions arrive.
     */
    fun rank(app: StoreApp): Int = when {
        app.fromRepository && !app.auto -> 0
        app.fromRepository -> 1
        app.source == StoreApp.SOURCE_IZZY -> 2
        else -> 3
    }

    /**
     * [apps] without the same package listed once for every catalogue that has it. Among the
     * places offering a package the first in line is taken, unless the app is installed:
     * Android updates an app only with a file signed like the installed one, so then a place
     * whose file is known to be signed that way goes first, and one whose signature is not
     * known before any that is known to differ. [installedSigners] gives the certificates of
     * an installed package, or null.
     *
     * Repositories of one place are never folded into one another: two of them releasing the
     * same package are two developers' builds, and stay two rows as long as a repository is
     * what is chosen. The same package on GitHub and on Codeberg, on the other hand, is
     * usually one project and its mirror; of two that are level in line the one with the
     * newer version is taken, since the mirror is the one that falls behind.
     */
    fun merged(apps: List<StoreApp>, installedSigners: (String) -> Set<String>?): List<Merged> {
        val result = ArrayList<Merged>(apps.size)
        val byPackage = LinkedHashMap<String, MutableList<StoreApp>>()
        for (app in apps) {
            val packageName = app.packageName
            if (packageName == null) {
                result += Merged(app, emptyList())
            } else {
                byPackage.getOrPut(packageName) { mutableListOf() } += app
            }
        }
        for ((packageName, offers) in byPackage) {
            if (offers.size == 1) {
                result += Merged(offers[0], emptyList())
                continue
            }
            val inLine = offers.sortedWith(
                compareBy<StoreApp>(::rank)
                    .thenByDescending { if (it.fromRepository) it.versionCode else 0 }
                    .thenBy { it.source != StoreApp.SOURCE_GITHUB }
            )
            val signers = installedSigners(packageName)
            val chosen = if (signers == null) {
                inLine[0]
            } else {
                inLine.firstOrNull { it.signer != null && it.signer in signers }
                    ?: inLine.firstOrNull { it.signer == null }
                    ?: inLine[0]
            }
            val kept = if (chosen.fromRepository) {
                inLine.filter { it.fromRepository && it.source == chosen.source }
            } else {
                listOf(chosen)
            }
            val others = inLine.filter { it !in kept }.map { it.source }.distinct()
            kept.forEach { result += Merged(it, others) }
        }
        return result
    }

    /**
     * The name for a release whose own is not known, taken from what was listed [before]: an
     * app keeps its name from one version to the next. Only an entry of the same package
     * will do, since a repository can release several apps, and one of the same kind of
     * release is preferred, since a beta may go by a name of its own.
     */
    fun inheritedLabel(before: List<StoreApp>, packageName: String?, prerelease: Boolean): String? {
        if (packageName == null) return null
        return before
            .filter { it.packageName == packageName && it.label != null }
            .minByOrNull { it.prerelease != prerelease }
            ?.label
    }

    /**
     * The downloads to show for one version of an app: those of the prereleases for a
     * [prerelease], and the rest, which the full releases account for, otherwise. [all] counts
     * every release and [beta] the prereleases among them.
     */
    fun downloads(all: Long, beta: Long, prerelease: Boolean): Long {
        val ofPrereleases = beta.coerceIn(0, maxOf(all, 0))
        return if (prerelease) ofPrereleases else maxOf(all, 0) - ofPrereleases
    }

    fun belongsTo(fullName: String, source: String): Boolean =
        if (SourceStore.isRepository(source)) {
            fullName.equals(source, ignoreCase = true)
        } else {
            fullName.substringBefore('/').equals(source, ignoreCase = true)
        }
}
