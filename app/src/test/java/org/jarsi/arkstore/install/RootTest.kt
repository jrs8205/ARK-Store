package org.jarsi.arkstore.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTest {

    @Test
    fun theOutputOfIdTellsRootFromAnyoneElse() {
        assertTrue(Root.isRoot("uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0\n"))
        assertTrue(Root.isRoot("  uid=0(root) gid=0(root)"))
        assertFalse(Root.isRoot("uid=2000(shell) gid=2000(shell)"))
        assertFalse(Root.isRoot("uid=10234(u0_a234) gid=10234(u0_a234)"))
        assertFalse(Root.isRoot(""))
        assertFalse(Root.isRoot("Permission denied"))
        // Only the user id counts, not a group named 0 further along.
        assertFalse(Root.isRoot("uid=2000(shell) gid=0(root)"))
    }

    @Test
    fun theVersionOfSuNamesItsProvider() {
        assertEquals("Magisk 27.0", Root.providerName("27.0:MAGISKSU\n"))
        assertEquals("KernelSU 1.0.3", Root.providerName("1.0.3:KernelSU"))
        assertEquals("APatch 10927", Root.providerName("10927:APatch"))
        assertEquals("16 su", Root.providerName("16 su"))
        assertEquals("", Root.providerName(""))
        // A version that is too long to be one is cut, so that the settings cannot be flooded.
        assertEquals(40, Root.providerName("x".repeat(500)).length)
    }
}
