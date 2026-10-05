package org.jarsi.arkstore.data

import android.os.Build
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
    fun offered(stable: StoreApp?, beta: StoreApp?, includeBeta: Boolean, android: Android): StoreApp? {
        if (!includeBeta || beta == null) return stable
        if (stable == null) return beta
        if (!upgrades(beta, stable)) return stable
        // A prerelease this Android cannot run does not replace a full release it can. When
        // it can run neither, the newest is shown as the one that needs a newer Android.
        return if (runsOn(stable, android) && !runsOn(beta, android)) stable else beta
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

    /**
     * The Android a device runs: its API level [sdk] and, for a preview of Android, its
     * [codename], which is "REL" for a release.
     */
    data class Android(val sdk: Int, val codename: String = RELEASE) {
        companion object {
            const val RELEASE = "REL"

            /** The Android this device runs. */
            val THIS: Android by lazy { Android(Build.VERSION.SDK_INT, Build.VERSION.CODENAME ?: RELEASE) }
        }
    }

    /**
     * Whether a file whose manifest names [minSdk] as the lowest Android API level it runs on,
     * or the preview of Android [codename] as the lowest, either null when the index does not
     * tell it, runs on [android]. A file not yet read for it is offered, as every file was
     * before the index read it. A file for a preview runs on that preview alone, not on any
     * release, however new: Android refuses it.
     */
    fun runsOn(minSdk: Int?, codename: String?, android: Android): Boolean = when {
        codename != null -> codename == android.codename
        else -> minSdk == null || minSdk <= android.sdk
    }

    /** Whether the file offered of [app] runs on [android]; see [runsOn]. */
    fun runsOn(app: StoreApp, android: Android): Boolean = runsOn(app.minSdk, app.minSdkCodename, android)

    /**
     * Whether an app is listed at all, given its [status] and whether its file [runs] on this
     * Android. One the device cannot run is listed only when the app is installed, to say
     * that its newest version needs a newer Android; one not installed, which could not be
     * installed, is left out.
     */
    fun listed(status: AppStatus, runs: Boolean): Boolean =
        runs || (status != AppStatus.NOT_INSTALLED && status != AppStatus.OTHER_APP)

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
     * [deviceAbis] and Android [sdk], or null when none is built for its architecture: the
     * highest version among those the device can run. A file that names no architecture runs
     * on all of them. When none of the files for the architecture runs on its Android, the
     * newest of them is named all the same, as the version an installed app would need a
     * newer Android for; see [listed].
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
        val forDevice = apks.withIndex().filter { (_, apk) ->
            apk.abis.isEmpty() || apk.abis.any { it in deviceAbis }
        }
        val runnable = forDevice.filter { (_, apk) -> apk.minSdk <= sdk }
        if (runnable.isEmpty()) return forDevice.maxByOrNull { (_, apk) -> apk.versionCode }?.index
        val updating = installedSigners?.let { signers ->
            runnable.filter { (_, apk) -> apk.signer == null || sameSigner(apk.signer, signers) }
        }
        return (updating?.takeIf { it.isNotEmpty() } ?: runnable)
            .maxByOrNull { (_, apk) -> apk.versionCode }
            ?.index
    }

    /**
     * A catalogue's known signer describes a file signed by that key alone. It matches only
     * the entire current signer set; sharing one key with a multiply signed app is not enough.
     */
    fun sameSigner(signer: String?, installed: Set<String>?): Boolean =
        signer != null && installed == setOf(signer)

    /**
     * Whether a downloaded file, signed with the certificates [file], may update the installed
     * app that has been signed with the certificates [installed], or null when it is not
     * installed. Both are read as the system reports them: for a file that carries the
     * history of its keys that is the first key the app had, for one that carries no history
     * its present key. [installed] holds every key the app has had, present one included; see
     * InstalledApps.keysHeld.
     *
     * Android takes a file signed with the present key whether or not it carries the history,
     * so only a file signed with a key the app never had is known to be refused. Whether a
     * key the app gave up still does is for the system to say.
     */
    fun mayUpdate(file: Collection<String>, installed: Set<String>?): Boolean =
        installed == null || file.isEmpty() || file.any { it in installed }

    /**
     * The name of the file an app's download is kept in until it is installed. Every
     * [fullName] has a name of its own, whatever characters it is made of: two downloads
     * running at once must never write to the same file.
     */
    fun downloadName(fullName: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(fullName.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) } + ".apk"
    }

    /**
     * One app of the list, with the other places that offer the same package. [otherBuild]
     * says the row is another project's build of an installed package whose own build is
     * known from its key: it cannot update the installed app, whatever its own key, which
     * may not be known yet.
     */
    data class Merged(val app: StoreApp, val alsoFrom: List<String>, val otherBuild: Boolean = false)

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
     *
     * A catalogue names the repository its files come from. An entry naming a repository
     * that is not among those offering the package is another project, such as the original
     * of a fork that kept the package name, and is listed on its own rather than folded under
     * the fork; see [projects]. When the installed app's key is known to be one project's,
     * the rows of the other projects are marked as [Merged.otherBuild]: a fork whose key is
     * not known yet must not be offered as the update of the original.
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
            val signers = installedSigners(packageName)
            val projects = projects(offers)
            val own = projects.filter { project ->
                project.any { sameSigner(it.signer, signers) }
            }
            for (project in projects) {
                val otherBuild = own.isNotEmpty() && own.none { it === project }
                result += fold(project, signers).map { if (otherBuild) it.copy(otherBuild = true) else it }
            }
        }
        return result
    }

    /**
     * The offers of one package split into the projects they belong to. The repositories are
     * one project (a project and its mirrors; those of one place are kept apart by [fold]). A
     * catalogue entry joins them when the repository it names is among them, stands with the
     * other entries naming the same repository otherwise, and one naming no repository joins
     * the first project in line, since it may be any of them.
     */
    private fun projects(offers: List<StoreApp>): List<List<StoreApp>> {
        val repositories = offers.filter { it.fromRepository }
        val named = repositories.mapNotNull(::project).toSet()
        val byProject = LinkedHashMap<String, MutableList<StoreApp>>()
        if (repositories.isNotEmpty()) byProject[REPOSITORIES] = repositories.toMutableList()
        val unnamed = mutableListOf<StoreApp>()
        for (app in offers) {
            if (app.fromRepository) continue
            val project = project(app)
            when {
                project == null -> unnamed += app
                project in named -> byProject.getValue(REPOSITORIES) += app
                else -> byProject.getOrPut(project) { mutableListOf() } += app
            }
        }
        val projects: MutableList<MutableList<StoreApp>> = byProject.values.toMutableList()
        if (unnamed.isNotEmpty()) {
            if (projects.isEmpty()) projects += mutableListOf<StoreApp>()
            projects[0] += unnamed
        }
        return projects
    }

    private const val REPOSITORIES = ""

    /** The rows of one project of one package; see [merged]. */
    private fun fold(offers: List<StoreApp>, signers: Set<String>?): List<Merged> {
        if (offers.size == 1) return listOf(Merged(offers[0], emptyList()))
        val inLine = offers.sortedWith(
            compareBy<StoreApp>(::rank)
                .thenByDescending { if (it.fromRepository) it.versionCode else 0 }
                .thenBy { it.source != StoreApp.SOURCE_GITHUB }
        )
        val chosen = if (signers == null) {
            inLine[0]
        } else {
            inLine.firstOrNull { sameSigner(it.signer, signers) }
                ?: inLine.firstOrNull { it.signer == null }
                ?: inLine[0]
        }
        val kept = if (chosen.fromRepository) {
            inLine.filter { it.fromRepository && it.source == chosen.source }
        } else {
            listOf(chosen)
        }
        return kept.map { row ->
            // A catalogue entry stands behind the repository it names, or behind any when it
            // names none; the other repositories are the project's mirrors.
            val project = project(row)
            val withIt = inLine.filter { it === row || it.fromRepository || project(it).let { named -> named == null || named == project } }
            val others = withIt.filter { it !in kept }.map { it.source }.distinct()
            // The icon comes from any place that has one: the app is the same wherever it
            // comes from, and few places tell its icon.
            val icon = withIt.firstNotNullOfOrNull { it.icon }
            Merged(if (row.icon == null && icon != null) row.copy(icon = icon) else row, others)
        }
    }

    private val HOSTS = mapOf(
        StoreApp.SOURCE_GITHUB to "github.com",
        StoreApp.SOURCE_CODEBERG to "codeberg.org",
        StoreApp.SOURCE_GITLAB to "gitlab.com"
    )

    /**
     * The repository [app] comes from or, for a catalogue entry, the one it names as the
     * source of its files, as "host/owner/repo" in lower case; null for a catalogue entry
     * that names no repository on one of the places (a website, or the catalogue's own page).
     * A GitLab project keeps its whole path, up to the "-" that begins its pages.
     */
    fun project(app: StoreApp): String? {
        if (app.fromRepository) return "${HOSTS.getValue(app.source)}/${app.repoPath}".lowercase()
        val url = app.repoUrl.trim().lowercase()
            .substringBefore('#').substringBefore('?')
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val host = url.substringBefore('/')
        val place = HOSTS.entries.firstOrNull { it.value == host }?.key ?: return null
        val segments = url.substringAfter('/', "").split('/').filter { it.isNotEmpty() }.takeWhile { it != "-" }
        if (segments.size < 2) return null
        val depth = if (place == StoreApp.SOURCE_GITLAB) segments.size else 2
        return "$host/" + segments.take(depth).joinToString("/").removeSuffix(".git")
    }

    /** The places apps are offered from, in the order their chips are shown. */
    val PLACES = listOf(
        StoreApp.SOURCE_GITHUB, StoreApp.SOURCE_CODEBERG, StoreApp.SOURCE_GITLAB,
        StoreApp.SOURCE_IZZY, StoreApp.SOURCE_FDROID
    )

    /** Whether [app] is offered from [place]: shown from it, or [alsoFrom] there as well. */
    fun offeredFrom(app: StoreApp, alsoFrom: List<String>, place: String): Boolean =
        app.source == place || place in alsoFrom

    /**
     * What an app without an icon is shown by: the first letter or digit of its [title]. A
     * title may begin with a symbol or an emoji, which says nothing on its own.
     */
    fun initial(title: String): String =
        title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: ""

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
