package org.jarsi.arkstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarksTest {

    private val unknown = testApp(fullName = "owner/app", packageName = null)
    private val known = testApp(fullName = "owner/app", packageName = "org.example")

    @Test
    fun bookmarkFollowsTheAppOnceItsPackageIsKnown() {
        val keys = Bookmarks.toggled(emptySet(), unknown)
        assertEquals(setOf("owner/app"), keys)
        assertTrue(Bookmarks.marked(known, keys))
        // From another place, the same package is the same app.
        assertTrue(Bookmarks.marked(testApp(fullName = "fdroid:org.example", packageName = "org.example"),
            Bookmarks.toggled(emptySet(), known)))
    }

    @Test
    fun takingTheBookmarkAwayClearsBothKeys() {
        val keys = Bookmarks.toggled(setOf("owner/app"), known)
        assertEquals(emptySet<String>(), keys)
        assertFalse(Bookmarks.marked(unknown, keys))
    }

    @Test
    fun bookmarkIsKeyedByThePackageWhenKnown() {
        assertEquals(setOf("org.example"), Bookmarks.toggled(emptySet(), known))
    }
}
