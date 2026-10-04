package org.jarsi.arkstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRulesTest {

    private fun app(
        fullName: String = "owner/app",
        packageName: String? = "org.example",
        versionCode: Long = 1,
        prerelease: Boolean = false,
        auto: Boolean = false
    ) = testApp(fullName, packageName, versionCode, prerelease, auto)

    @Test
    fun fileRunsOnAnAndroidNoOlderThanItsLowest() {
        assertTrue(CatalogRules.runsOn(minSdk = 26, sdk = 26))
        assertTrue(CatalogRules.runsOn(minSdk = 26, sdk = 35))
        assertFalse(CatalogRules.runsOn(minSdk = 27, sdk = 26))
        // A file whose lowest Android is not known is offered, as before it was read.
        assertTrue(CatalogRules.runsOn(minSdk = null, sdk = 21))
    }

    @Test
    fun offersThePrereleaseOnlyWhenBetaVersionsAreWanted() {
        val stable = app(versionCode = 5)
        val beta = app(versionCode = 7, prerelease = true)
        assertSame(beta, CatalogRules.offered(stable, beta, includeBeta = true))
        assertSame(stable, CatalogRules.offered(stable, beta, includeBeta = false))
        assertSame(stable, CatalogRules.offered(stable, null, includeBeta = true))
    }

    @Test
    fun prereleaseFromAnOlderBranchDoesNotReplaceTheFullRelease() {
        val stable = app(versionCode = 200)
        val older = app(versionCode = 191, prerelease = true)
        assertSame(stable, CatalogRules.offered(stable, older, includeBeta = true))
    }

    @Test
    fun prereleaseWithTheSameVersionCodeDoesNotReplaceTheFullRelease() {
        val stable = app(versionCode = 8)
        val nightly = app(versionCode = 8, prerelease = true)
        assertSame(stable, CatalogRules.offered(stable, nightly, includeBeta = true))
    }

    @Test
    fun prereleaseOfAnotherPackageDoesNotReplaceTheFullRelease() {
        val stable = app(packageName = "org.example", versionCode = 5)
        val other = app(packageName = "org.example.beta", versionCode = 9, prerelease = true)
        assertSame(stable, CatalogRules.offered(stable, other, includeBeta = true))
    }

    @Test
    fun unreadableManifestLeavesTheFullRelease() {
        val stable = app(versionCode = 5)
        val unknown = app(packageName = null, versionCode = 0, prerelease = true)
        assertSame(stable, CatalogRules.offered(stable, unknown, includeBeta = true))
        val unknownStable = app(packageName = null, versionCode = 0)
        val beta = app(versionCode = 7, prerelease = true)
        assertSame(unknownStable, CatalogRules.offered(unknownStable, beta, includeBeta = true))
    }

    @Test
    fun repositoryWithOnlyAPrereleaseIsOfferedAsBeta() {
        val beta = app(versionCode = 1, prerelease = true)
        assertSame(beta, CatalogRules.offered(null, beta, includeBeta = true))
        assertNull(CatalogRules.offered(null, beta, includeBeta = false))
    }

    @Test
    fun automaticallyFoundAppIsHiddenUnlessWanted() {
        val found = app(fullName = "someone/app", auto = true)
        assertNull(CatalogRules.shown(found, listOf("jrs8205"), includeAuto = false))
        assertSame(found, CatalogRules.shown(found, listOf("jrs8205"), includeAuto = true))
    }

    @Test
    fun automaticallyFoundAppFromAUsersSourceIsShownAsTheirOwn() {
        val found = app(fullName = "Someone/App", auto = true)
        for (source in listOf("someone", "someone/app")) {
            val shown = CatalogRules.shown(found, listOf("jrs8205", source), includeAuto = false)
            assertEquals(found.copy(auto = false), shown)
        }
        // Another repository of the same account is not covered by a single-repository source.
        assertNull(CatalogRules.shown(found, listOf("someone/other"), includeAuto = false))
    }

    @Test
    fun publishedAppIsAlwaysShown() {
        val published = app()
        assertSame(published, CatalogRules.shown(published, emptyList(), includeAuto = false))
    }

    @Test
    fun nameIsInheritedOnlyWithinAPackage() {
        val stable = app().copy(label = "Example")
        val beta = app(prerelease = true).copy(label = "Example Beta")
        val before = listOf(stable, beta)
        assertEquals("Example", CatalogRules.inheritedLabel(before, stable.packageName, prerelease = false))
        assertEquals("Example Beta", CatalogRules.inheritedLabel(before, stable.packageName, prerelease = true))
        // The other kind of release will do when its own has no name.
        assertEquals("Example", CatalogRules.inheritedLabel(listOf(stable), stable.packageName, prerelease = true))
        assertNull(CatalogRules.inheritedLabel(before, "org.another", prerelease = false))
        assertNull(CatalogRules.inheritedLabel(before, null, prerelease = false))
    }

    private fun catalogue(source: String, signer: String?, packageName: String = "org.example") =
        app(fullName = "$source:$packageName", packageName = packageName)
            .copy(source = source, signer = signer)

    private fun merged(apps: List<StoreApp>, installed: Set<String>? = null) =
        CatalogRules.merged(apps) { installed }

    @Test
    fun appOfACatalogueIsShownOnlyWhileTheCatalogueIsOn() {
        val fdroid = catalogue(StoreApp.SOURCE_FDROID, "f")
        assertNull(CatalogRules.shown(fdroid, emptyList(), includeAuto = true))
        assertSame(
            fdroid,
            CatalogRules.shown(fdroid, emptyList(), includeAuto = false, setOf(StoreApp.SOURCE_FDROID))
        )
    }

    @Test
    fun samePackageIsListedOnceFromTheFirstInLine() {
        val published = app()
        val izzy = catalogue(StoreApp.SOURCE_IZZY, "dev")
        val fdroid = catalogue(StoreApp.SOURCE_FDROID, "f")
        val other = catalogue(StoreApp.SOURCE_FDROID, "f", packageName = "org.other")

        val rows = merged(listOf(fdroid, other, izzy, published))
        assertEquals(listOf(other, published), rows.map { it.app }.sortedBy { it.fullName })
        val row = rows.single { it.app === published }
        assertEquals(listOf(StoreApp.SOURCE_IZZY, StoreApp.SOURCE_FDROID), row.alsoFrom)

        assertSame(izzy, merged(listOf(fdroid, izzy)).single().app)
    }

    @Test
    fun installedAppStaysWithThePlaceThatSignsItsFilesTheSameWay() {
        val found = app(auto = true)
        val izzy = catalogue(StoreApp.SOURCE_IZZY, "dev")
        val fdroid = catalogue(StoreApp.SOURCE_FDROID, "f")
        val all = listOf(found, izzy, fdroid)

        val fromFdroid = merged(all, installed = setOf("f")).single()
        assertSame(fdroid, fromFdroid.app)
        assertEquals(listOf(StoreApp.SOURCE_GITHUB, StoreApp.SOURCE_IZZY), fromFdroid.alsoFrom)
        // The developer's key is what both the repository and IzzyOnDroid offer; the
        // catalogue is the one that is known to.
        assertSame(izzy, merged(all, installed = setOf("dev")).single().app)
        // A key nobody is known to use: the repository, whose key is not known, may yet match.
        assertSame(found, merged(all, installed = setOf("x")).single().app)
        assertSame(izzy, merged(listOf(izzy, fdroid), installed = setOf("x")).single().app)
    }

    @Test
    fun iconComesFromAnyPlaceThatHasOne() {
        val published = app()
        val icon = AppIcon("https://x/icons/org.example.png", null, null)
        val izzy = catalogue(StoreApp.SOURCE_IZZY, "dev").copy(icon = icon)
        val row = merged(listOf(published, izzy)).single()
        assertEquals(published.fullName, row.app.fullName)
        assertEquals(icon, row.app.icon)
        // An icon of the app's own is kept.
        val own = AppIcon(null, "https://x/icons/fg.json", "#ff112233")
        assertEquals(own, merged(listOf(published.copy(icon = own), izzy)).single().app.icon)
        assertNull(merged(listOf(published)).single().app.icon)
    }

    @Test
    fun appWithoutAnIconIsShownByItsFirstLetterOrDigit() {
        assertEquals("B", CatalogRules.initial("bitwarden"))
        assertEquals("7", CatalogRules.initial("7-Zip"))
        assertEquals("A", CatalogRules.initial("🐾 App"))
        assertEquals("Ä", CatalogRules.initial("äiti"))
        assertEquals("", CatalogRules.initial("***"))
    }

    @Test
    fun appIsOfferedFromThePlaceItIsShownFromAndThoseItIsAlsoAt() {
        val found = app(auto = true)
        assertTrue(CatalogRules.offeredFrom(found, listOf(StoreApp.SOURCE_FDROID), StoreApp.SOURCE_GITHUB))
        assertTrue(CatalogRules.offeredFrom(found, listOf(StoreApp.SOURCE_FDROID), StoreApp.SOURCE_FDROID))
        assertFalse(CatalogRules.offeredFrom(found, emptyList(), StoreApp.SOURCE_IZZY))
        assertEquals(listOf("github", "codeberg", "gitlab", "izzy", "fdroid"), CatalogRules.PLACES)
    }

    @Test
    fun repositoriesReleasingTheSamePackageStayApart() {
        val one = app(fullName = "one/app")
        val two = app(fullName = "two/app")
        val fdroid = catalogue(StoreApp.SOURCE_FDROID, "f")
        assertEquals(listOf(one, two), merged(listOf(one, two, fdroid)).map { it.app })
        assertEquals(listOf(fdroid), merged(listOf(one, two, fdroid), installed = setOf("f")).map { it.app })
        val unknown = app(packageName = null)
        assertEquals(2, merged(listOf(unknown, unknown)).size)
    }

    @Test
    fun originalOfAForkThatKeptThePackageNameIsListedOnItsOwn() {
        val fork = app(fullName = "someone/Shizuku-fork", auto = true)
        val izzy = catalogue(StoreApp.SOURCE_IZZY, "dev")
            .copy(repoUrl = "https://github.com/RikkaApps/Shizuku")
        val fdroid = catalogue(StoreApp.SOURCE_FDROID, "f")
            .copy(repoUrl = "https://github.com/RikkaApps/Shizuku/")
        val rows = merged(listOf(fork, izzy, fdroid))
        assertEquals(listOf(fork, izzy), rows.map { it.app })
        assertEquals(emptyList<String>(), rows[0].alsoFrom)
        assertEquals(listOf(StoreApp.SOURCE_FDROID), rows[1].alsoFrom)
        // Installed from the original's developer: the original is offered, and the fork,
        // whose key is not known yet, is another build that cannot update it.
        val installedOriginal = merged(listOf(fork, izzy, fdroid), installed = setOf("dev"))
        assertEquals(listOf(fork, izzy), installedOriginal.map { it.app })
        assertEquals(listOf(true, false), installedOriginal.map { it.otherBuild })
        // Not installed, or installed with a key no place is known to use: nothing is presumed.
        assertEquals(listOf(false, false), rows.map { it.otherBuild })
        assertEquals(listOf(false, false), merged(listOf(fork, izzy, fdroid), installed = setOf("x")).map { it.otherBuild })

        // The original's own repository, once found, takes the entries that name it, and the
        // fork beside it does not.
        val own = app(fullName = "RikkaApps/Shizuku", auto = true)
        val found = merged(listOf(fork, own, izzy, fdroid))
        assertEquals(listOf(fork, own), found.map { it.app })
        assertEquals(emptyList<String>(), found[0].alsoFrom)
        assertEquals(listOf(StoreApp.SOURCE_IZZY, StoreApp.SOURCE_FDROID), found[1].alsoFrom)
        val icon = AppIcon("https://x/icons/org.example.png", null, null)
        val withIcon = merged(listOf(fork, own, izzy.copy(icon = icon)))
        assertNull(withIcon[0].app.icon)
        assertEquals(icon, withIcon[1].app.icon)

        // An entry naming no repository may be any of them: it joins the first in line.
        val site = catalogue(StoreApp.SOURCE_FDROID, "f").copy(repoUrl = "https://example.org/app")
        assertEquals(listOf(StoreApp.SOURCE_FDROID), merged(listOf(fork, site)).single().alsoFrom)
        assertEquals(listOf(StoreApp.SOURCE_FDROID), merged(listOf(izzy, site)).single().alsoFrom)
        // Two catalogues naming different repositories are two rows.
        assertEquals(2, merged(listOf(izzy, fdroid.copy(repoUrl = "https://codeberg.org/other/app"))).size)
    }

    @Test
    fun projectIsTheRepositoryAListingComesFromOrNames() {
        assertEquals("github.com/owner/app", CatalogRules.project(app(fullName = "Owner/App")))
        assertEquals("codeberg.org/owner/app", CatalogRules.project(codeberg(1)))
        val izzy = catalogue(StoreApp.SOURCE_IZZY, "dev")
        fun named(url: String) = CatalogRules.project(izzy.copy(repoUrl = url))
        assertEquals("github.com/rikkaapps/shizuku", named("https://github.com/RikkaApps/Shizuku.git"))
        assertEquals("github.com/rikkaapps/shizuku", named("http://www.github.com/RikkaApps/Shizuku/tree/master"))
        assertEquals("github.com/rikkaapps/shizuku", named(" https://github.com/RikkaApps/Shizuku#readme"))
        assertEquals("gitlab.com/group/sub/app", named("https://gitlab.com/group/sub/app/-/releases"))
        assertNull(named("https://example.org/app"))
        assertNull(named("https://github.com/RikkaApps"))
        assertNull(named("https://apt.izzysoft.de/fdroid/index/apk/org.example"))
    }

    private fun codeberg(versionCode: Long, auto: Boolean = true) =
        app(fullName = "codeberg:owner/app", versionCode = versionCode, auto = auto)
            .copy(source = StoreApp.SOURCE_CODEBERG)

    @Test
    fun repositoryElsewhereIsReadLikeOneOnGitHub() {
        val found = codeberg(1)
        assertEquals("owner/app", found.repoPath)
        assertEquals("owner", found.owner)
        assertEquals("app", found.repo)
        assertTrue(found.fromRepository)
        // It is not covered by a source on this device, which names GitHub accounts.
        assertNull(CatalogRules.shown(found, listOf("owner"), includeAuto = true))
        assertSame(
            found,
            CatalogRules.shown(found, emptyList(), includeAuto = false, setOf(StoreApp.SOURCE_CODEBERG))
        )
        val published = codeberg(1, auto = false)
        assertSame(published, CatalogRules.shown(published, emptyList(), includeAuto = false))
    }

    @Test
    fun projectInASubgroupKeepsItsWholePath() {
        val project = app(fullName = "gitlab:group/sub/app", auto = true)
            .copy(source = StoreApp.SOURCE_GITLAB)
        assertTrue(project.fromRepository)
        assertEquals("group/sub/app", project.repoPath)
        assertEquals("group", project.owner)
        assertEquals("sub/app", project.repo)
        assertNull(CatalogRules.shown(project, emptyList(), includeAuto = true))
    }

    @Test
    fun mirrorOnAnotherPlaceIsFoldedIntoTheNewerOne() {
        val github = app(auto = true, versionCode = 5)
        assertSame(github, merged(listOf(codeberg(5), github)).single().app)
        val moved = merged(listOf(github, codeberg(6))).single()
        assertEquals(StoreApp.SOURCE_CODEBERG, moved.app.source)
        assertEquals(listOf(StoreApp.SOURCE_GITHUB), moved.alsoFrom)
        // The developer's own publication goes first however old it is.
        val published = app(versionCode = 4)
        assertSame(published, merged(listOf(codeberg(6), published)).single().app)
    }

    @Test
    fun newestFileTheDeviceCanRunIsPicked() {
        fun apk(code: Long, vararg abis: String, minSdk: Int = 23) =
            CatalogRules.CatalogueApk(code, abis.toList(), minSdk)
        val perAbi = listOf(apk(4, "x86_64"), apk(3, "arm64-v8a"), apk(2, "armeabi-v7a"))
        val arm = listOf("arm64-v8a", "armeabi-v7a")
        assertEquals(1, CatalogRules.pickCatalogueApk(perAbi, arm, sdk = 34))
        assertEquals(2, CatalogRules.pickCatalogueApk(perAbi, listOf("armeabi-v7a"), sdk = 34))
        assertNull(CatalogRules.pickCatalogueApk(perAbi, listOf("riscv64"), sdk = 34))
        assertEquals(0, CatalogRules.pickCatalogueApk(listOf(apk(1)), arm, sdk = 34))
        assertNull(CatalogRules.pickCatalogueApk(listOf(apk(1, minSdk = 35)), arm, sdk = 34))
        assertEquals(
            1,
            CatalogRules.pickCatalogueApk(listOf(apk(9, minSdk = 35), apk(8)), arm, sdk = 34)
        )
    }

    @Test
    fun installedAppIsOfferedTheNewestFileSignedLikeIt() {
        fun apk(code: Long, signer: String?) =
            CatalogRules.CatalogueApk(code, emptyList(), 23, signer)
        val abis = listOf("arm64-v8a")
        val files = listOf(apk(9, "new"), apk(8, "old"), apk(7, "old"))
        assertEquals(0, CatalogRules.pickCatalogueApk(files, abis, sdk = 34))
        assertEquals(1, CatalogRules.pickCatalogueApk(files, abis, sdk = 34, installedSigners = setOf("old")))
        assertEquals(0, CatalogRules.pickCatalogueApk(files, abis, sdk = 34, installedSigners = setOf("new")))
        // Nothing is signed like the installed app; the newest is shown, as one that will not do.
        assertEquals(0, CatalogRules.pickCatalogueApk(files, abis, sdk = 34, installedSigners = setOf("other")))
        // A file whose signature is not known may still turn out to fit.
        val unknown = listOf(apk(9, null), apk(8, "old"))
        assertEquals(0, CatalogRules.pickCatalogueApk(unknown, abis, sdk = 34, installedSigners = setOf("old")))
    }

    @Test
    fun oneOfSeveralInstalledSignersDoesNotMakeACatalogueFilePreferred() {
        val files = listOf(
            CatalogRules.CatalogueApk(9, emptyList(), 23, "a"),
            CatalogRules.CatalogueApk(8, emptyList(), 23, null)
        )
        assertEquals(1, CatalogRules.pickCatalogueApk(files, listOf("arm64-v8a"), 34, setOf("a", "b")))
        assertEquals(0, CatalogRules.pickCatalogueApk(files, listOf("arm64-v8a"), 34, setOf("a")))
    }

    @Test
    fun oneOfSeveralInstalledSignersDoesNotIdentifyTheInstalledProject() {
        val repository = app()
        val izzy = catalogue(StoreApp.SOURCE_IZZY, "a")
        assertSame(repository, merged(listOf(repository, izzy), setOf("a", "b")).single().app)

        val fork = app(fullName = "someone/fork")
        val original = izzy.copy(repoUrl = "https://github.com/original/app")
        val rows = merged(listOf(fork, original), setOf("a", "b"))
        assertEquals(2, rows.size)
        assertTrue(rows.none { it.otherBuild })
    }

    @Test
    fun downloadedFileSignedWithAKeyTheAppHasHadMayUpdateIt() {
        // The installed app has had the keys a, b and c, in that order, and is signed with c now.
        val held = setOf("a", "b", "c")
        // A file carrying its key history reports the first key; one without reports its own.
        assertTrue(CatalogRules.mayUpdate(listOf("a"), held))
        assertTrue(CatalogRules.mayUpdate(listOf("c"), held))
        // A key the app gave up is for the system to judge, a key it never had is refused here.
        assertTrue(CatalogRules.mayUpdate(listOf("b"), held))
        assertFalse(CatalogRules.mayUpdate(listOf("d"), held))
        assertTrue(CatalogRules.mayUpdate(listOf("d", "c"), held))
        // Nothing is known of an app that is not installed or a file whose signers cannot be read.
        assertTrue(CatalogRules.mayUpdate(listOf("d"), null))
        assertTrue(CatalogRules.mayUpdate(emptyList(), held))
    }

    @Test
    fun everyAppDownloadsToAFileOfItsOwn() {
        val name = CatalogRules.downloadName("gitlab:group/sub/app")
        assertEquals(name, CatalogRules.downloadName("gitlab:group/sub/app"))
        assertNotEquals(name, CatalogRules.downloadName("gitlab:group/sub_app"))
        assertNotEquals(name, CatalogRules.downloadName("gitlab_group/sub/app"))
        assertTrue(Regex("[0-9a-f]{32}[.]apk").matches(name))
    }

    @Test
    fun fullReleasesAndPrereleasesCountTheirOwnDownloads() {
        assertEquals(30L, CatalogRules.downloads(all = 35, beta = 5, prerelease = false))
        assertEquals(5L, CatalogRules.downloads(all = 35, beta = 5, prerelease = true))
        // Numbers that do not add up never go below zero.
        assertEquals(0L, CatalogRules.downloads(all = 3, beta = 9, prerelease = false))
        assertEquals(3L, CatalogRules.downloads(all = 3, beta = 9, prerelease = true))
    }

    @Test
    fun sourceCoversAnAccountOrASingleRepository() {
        assertTrue(CatalogRules.belongsTo("Owner/app", "owner"))
        assertTrue(CatalogRules.belongsTo("owner/App", "owner/app"))
        assertFalse(CatalogRules.belongsTo("owner/app", "owner/other"))
        assertFalse(CatalogRules.belongsTo("owner2/app", "owner"))
    }
}
