package org.jarsi.arkstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkTest {

    private val base = "https://arkstore.jrs8205.com/app/"

    @Test
    fun linkNamesTheAppAndItsPackage() {
        val link = ShareLink.of(testApp(fullName = "owner/app", packageName = "org.example"))
        assertEquals("$base?id=owner/app&p=org.example", link?.address)
    }

    @Test
    fun linkOfAnAppWhosePackageIsNotKnownNamesTheAppAlone() {
        val link = ShareLink.of(testApp(fullName = "codeberg:owner/app", packageName = null))
        assertEquals("$base?id=codeberg:owner/app", link?.address)
    }

    @Test
    fun appWhoseNameWouldNotSurviveALinkGetsNone() {
        assertNull(ShareLink.of(testApp(fullName = "owner/my app")))
        assertNull(ShareLink.of(testApp(fullName = "owner/../app")))
        assertNull(ShareLink.of(testApp(fullName = "owner/" + "a".repeat(200))))
    }

    @Test
    fun packageThatWouldNotSurviveALinkIsLeftOut() {
        val link = ShareLink.of(testApp(fullName = "owner/app", packageName = "not a package"))
        assertEquals("$base?id=owner/app", link?.address)
    }

    @Test
    fun linkIsReadBackAsItWasGivenOut() {
        val apps = listOf(
            testApp(fullName = "owner/app", packageName = "org.example"),
            testApp(fullName = "codeberg:owner/app.name", packageName = "org.example.two"),
            testApp(fullName = "gitlab:group/sub-group/project_1", packageName = null),
            testApp(fullName = "fdroid:org.example.app", packageName = "org.example.app"),
            testApp(fullName = "izzy:org.example.app", packageName = "org.example.app")
        )
        for (app in apps) {
            val link = ShareLink.of(app)!!
            assertEquals(link, ShareLink.parse(link.address))
            assertEquals(app.fullName, link.fullName)
            assertEquals(app.packageName, link.packageName)
        }
    }

    @Test
    fun linkIsReadHoweverItsAddressIsWritten() {
        val expected = ShareLink("owner/app", "org.example")
        assertEquals(expected, ShareLink.parse("https://arkstore.jrs8205.com/app?id=owner/app&p=org.example"))
        assertEquals(expected, ShareLink.parse("HTTPS://ArkStore.JRS8205.com/app/?id=owner/app&p=org.example"))
        assertEquals(expected, ShareLink.parse("$base?id=owner%2Fapp&p=org.example"))
        assertEquals(expected, ShareLink.parse("$base?p=org.example&id=owner/app"))
        assertEquals(expected, ShareLink.parse("$base?utm=x&id=owner/app&p=org.example&other"))
        assertEquals(expected, ShareLink.parse("$base?id=owner/app&p=org.example#top"))
        assertEquals(expected, ShareLink.parse("https://arkstore.jrs8205.com:443/app/?id=owner/app&p=org.example"))
        assertEquals(ShareLink("codeberg:owner/app", null), ShareLink.parse("$base?id=codeberg%3Aowner%2Fapp"))
    }

    @Test
    fun linkToAnotherPlaceIsNotRead() {
        assertNull(ShareLink.parse("http://arkstore.jrs8205.com/app/?id=owner/app"))
        assertNull(ShareLink.parse("arkstore://arkstore.jrs8205.com/app/?id=owner/app"))
        assertNull(ShareLink.parse("https://jrs8205.com/app/?id=owner/app"))
        assertNull(ShareLink.parse("https://arkstore.jrs8205.com.example.org/app/?id=owner/app"))
        assertNull(ShareLink.parse("https://arkstore.jrs8205.com@example.org/app/?id=owner/app"))
        assertNull(ShareLink.parse("https://user@arkstore.jrs8205.com/app/?id=owner/app"))
        assertNull(ShareLink.parse("https://arkstore.jrs8205.com:8443/app/?id=owner/app"))
        assertNull(ShareLink.parse("https://arkstore.jrs8205.com/?id=owner/app"))
        assertNull(ShareLink.parse("https://arkstore.jrs8205.com/app/more?id=owner/app"))
        assertNull(ShareLink.parse("https://arkstore.jrs8205.com/apps/?id=owner/app"))
        assertNull(ShareLink.parse("/app/?id=owner/app"))
        assertNull(ShareLink.parse(""))
        assertNull(ShareLink.parse("not a link"))
    }

    @Test
    fun linkThatNamesNoAppIsNotRead() {
        assertNull(ShareLink.parse(base))
        assertNull(ShareLink.parse("$base?"))
        assertNull(ShareLink.parse("$base?id="))
        assertNull(ShareLink.parse("$base?id"))
        assertNull(ShareLink.parse("$base?p=org.example"))
        assertNull(ShareLink.parse("$base?ID=owner/app"))
    }

    @Test
    fun nameThatIsNotAnAppsIsNotRead() {
        assertNull(ShareLink.parse("$base?id=owner/../app"))
        assertNull(ShareLink.parse("$base?id=./app"))
        assertNull(ShareLink.parse("$base?id=owner//app"))
        assertNull(ShareLink.parse("$base?id=/owner/app"))
        assertNull(ShareLink.parse("$base?id=owner/app/"))
        assertNull(ShareLink.parse("$base?id=owner/my+app"))
        assertNull(ShareLink.parse("$base?id=owner/my%20app"))
        assertNull(ShareLink.parse("$base?id=owner/app%3Cscript%3E"))
        assertNull(ShareLink.parse("$base?id=owner/%E2%80%AEapp"))
        assertNull(ShareLink.parse("$base?id=https://example.org/app"))
        assertNull(ShareLink.parse("$base?id=Codeberg:owner/app"))
        assertNull(ShareLink.parse("$base?id=a:b:owner/app"))
        assertNull(ShareLink.parse("$base?id=:owner/app"))
        assertNull(ShareLink.parse("$base?id=owner/app%"))
        assertNull(ShareLink.parse("$base?id=owner/app%zz"))
        assertNull(ShareLink.parse("$base?id=owner/" + "a".repeat(200)))
        assertNull(ShareLink.parse("$base?id=" + List(12) { "a" }.joinToString("/")))
    }

    @Test
    fun nameGivenTwiceIsNotRead() {
        assertNull(ShareLink.parse("$base?id=owner/app&id=other/app"))
        assertNull(ShareLink.parse("$base?id=owner/app&p=org.example&p=org.other"))
    }

    @Test
    fun packageThatIsNotOneIsDroppedAndTheLinkStillRead() {
        assertEquals(ShareLink("owner/app", null), ShareLink.parse("$base?id=owner/app&p=example"))
        assertEquals(ShareLink("owner/app", null), ShareLink.parse("$base?id=owner/app&p=org..example"))
        assertEquals(ShareLink("owner/app", null), ShareLink.parse("$base?id=owner/app&p=org.1example"))
        assertEquals(ShareLink("owner/app", null), ShareLink.parse("$base?id=owner/app&p="))
        assertEquals(ShareLink("owner/app", null), ShareLink.parse("$base?id=owner/app&p=org." + "a".repeat(300)))
    }

    @Test
    fun endlessAddressIsNotRead() {
        assertNull(ShareLink.parse("$base?id=owner/app&x=" + "a".repeat(5000)))
    }

    @Test
    fun linkFindsTheAppItNames() {
        val wanted = testApp(fullName = "owner/app", packageName = "org.example")
        val apps = listOf(testApp(fullName = "other/app", packageName = "org.other"), wanted)
        assertEquals(wanted, ShareLink.pick(apps, ShareLink("owner/app", "org.example")))
        assertEquals(wanted, ShareLink.pick(apps, ShareLink("owner/app", null)))
    }

    @Test
    fun linkFindsTheAppUnderWhateverCaseItsNameWasTyped() {
        val wanted = testApp(fullName = "Owner/App", packageName = "org.example")
        val apps = listOf(testApp(fullName = "owner/app", packageName = "org.lower"), wanted)
        // The name as written wins over the same letters in another case.
        assertEquals(wanted, ShareLink.pick(apps, ShareLink("Owner/App", null)))
        assertEquals(wanted, ShareLink.pick(listOf(wanted), ShareLink("owner/app", null)))
    }

    @Test
    fun linkFindsTheAppOfferedFromAnotherPlaceByItsPackage() {
        val here = testApp(fullName = "owner/app", packageName = "org.example")
        val apps = listOf(testApp(fullName = "other/app", packageName = "org.other"), here)
        assertEquals(here, ShareLink.pick(apps, ShareLink("fdroid:org.example", "org.example")))
    }

    @Test
    fun appUnderTheNameWinsOverAnotherWithThePackage() {
        val named = testApp(fullName = "owner/app", packageName = "org.example")
        val packaged = testApp(fullName = "other/app", packageName = "org.other")
        assertEquals(named, ShareLink.pick(listOf(packaged, named), ShareLink("owner/app", "org.other")))
    }

    @Test
    fun linkToAnAppThatIsNotListedFindsNone() {
        val apps = listOf(testApp(fullName = "owner/app", packageName = "org.example"))
        assertNull(ShareLink.pick(apps, ShareLink("fdroid:org.missing", "org.missing")))
        assertNull(ShareLink.pick(apps, ShareLink("other/app", null)))
        assertNull(ShareLink.pick(emptyList(), ShareLink("owner/app", "org.example")))
        // An app whose package is not known is not every app without one.
        val unknown = listOf(testApp(fullName = "owner/app", packageName = null))
        assertNull(ShareLink.pick(unknown, ShareLink("other/app", null)))
    }

    @Test
    fun followedLinkLeadsToAListedAppWithoutWaitingForTheRefresh() {
        val app = testApp(fullName = "owner/app", packageName = "org.example")
        val followed = FollowedLink(ShareLink("owner/app", "org.example"), settledAt = 3)
        assertEquals(FollowedLink.Led.To(app), followed.leadsTo(listOf(app), refreshes = 2))
        assertEquals(FollowedLink.Led.To(app), followed.leadsTo(listOf(app), refreshes = 3))
    }

    @Test
    fun followedLinkWaitsForTheRefreshBeforeItsAppIsToldToBeMissing() {
        val others = listOf(testApp(fullName = "other/app", packageName = "org.other"))
        val followed = FollowedLink(ShareLink("owner/app", "org.example"), settledAt = 3)
        assertEquals(FollowedLink.Led.Nowhere, followed.leadsTo(emptyList(), refreshes = 2))
        assertEquals(FollowedLink.Led.Nowhere, followed.leadsTo(others, refreshes = 2))
        assertEquals(FollowedLink.Led.Astray, followed.leadsTo(others, refreshes = 3))
        assertEquals(FollowedLink.Led.Astray, followed.leadsTo(others, refreshes = 4))
    }

    @Test
    fun followedLinkFindsTheAppTheRefreshBrought() {
        val app = testApp(fullName = "owner/app", packageName = "org.example")
        val followed = FollowedLink(ShareLink("owner/app", "org.example"), settledAt = 1)
        assertEquals(FollowedLink.Led.Nowhere, followed.leadsTo(emptyList(), refreshes = 0))
        assertEquals(FollowedLink.Led.To(app), followed.leadsTo(listOf(app), refreshes = 1))
    }
}
