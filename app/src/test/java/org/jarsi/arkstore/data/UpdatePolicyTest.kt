package org.jarsi.arkstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val app = testApp("alice/app", packageName = "org.alice", versionCode = 12)

    @Test
    fun aSkippedVersionIsNotOfferedButTheNextOneIs() {
        val policy = UpdatePolicy.NONE.skip(app)
        assertTrue(policy.skips(app))
        assertFalse(policy.skips(app.copy(versionCode = 13)))
        assertFalse(policy.skips(app.copy(packageName = "org.bob")))
        assertFalse(policy.skips(app.copy(packageName = null)))
    }

    @Test
    fun anAppHeldBackIsNotOfferedAnyVersionUntilReleased() {
        val policy = UpdatePolicy.NONE.hold("org.alice", true)
        assertTrue(policy.skips(app))
        assertTrue(policy.skips(app.copy(versionCode = 99)))
        assertFalse(policy.hold("org.alice", false).skips(app))
    }

    @Test
    fun aSkipIsUndoneByOfferingAgain() {
        val policy = UpdatePolicy.NONE.skip(app).hold("org.alice", true)
        val released = policy.offer("org.alice")
        assertFalse(released.skips(app))
        assertEquals(UpdatePolicy.NONE, released)
    }

    @Test
    fun aPolicySurvivesThePreferences() {
        val policy = UpdatePolicy.NONE.skip(app).skip(app.copy(packageName = "org.bob", versionCode = 3)).hold("org.carol", true)
        assertEquals(policy, UpdatePolicy.of(policy.skippedEntries(), policy.held))
        assertEquals(setOf("org.alice@12", "org.bob@3"), policy.skippedEntries())
        // An entry that is not one is left out.
        assertEquals(UpdatePolicy.NONE.hold("org.carol", true), UpdatePolicy.of(setOf("broken", "org.x@many"), setOf("org.carol")))
    }

    @Test
    fun aPolicyTakenFromAnotherDeviceJoinsThisOne() {
        val here = UpdatePolicy.NONE.skip(app).hold("org.carol", true)
        val there = UpdatePolicy.NONE.skip(app.copy(versionCode = 14)).hold("org.dave", true)
        val joined = here.plus(there)
        assertTrue(joined.skips(app.copy(versionCode = 14)))
        assertFalse(joined.skips(app))
        assertEquals(setOf("org.carol", "org.dave"), joined.held)
    }
}
