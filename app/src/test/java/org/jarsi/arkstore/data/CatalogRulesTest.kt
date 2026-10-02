package org.jarsi.arkstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
