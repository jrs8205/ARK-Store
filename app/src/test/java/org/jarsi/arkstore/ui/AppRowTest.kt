package org.jarsi.arkstore.ui

import org.jarsi.arkstore.data.AppStatus
import org.jarsi.arkstore.data.InstalledVersion
import org.jarsi.arkstore.data.testApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRowTest {

    private val stable = testApp(versionCode = 5)

    private fun row(installed: Long?, betaVersion: Long?) = AppRow(
        app = stable,
        installed = installed?.let { InstalledVersion(it, null) },
        status = AppStatus.UP_TO_DATE,
        betaVersion = betaVersion
    )

    @Test
    fun installedPrereleaseIsABeta() {
        assertTrue(row(installed = 8, betaVersion = 8).betaInstalled)
        // An earlier beta than the newest one is a beta all the same.
        assertTrue(row(installed = 6, betaVersion = 8).betaInstalled)
    }

    @Test
    fun newerVersionFromElsewhereIsNotCalledABeta() {
        val fromAnotherStore = row(installed = 50, betaVersion = 8)
        assertTrue(fromAnotherStore.newerInstalled)
        assertFalse(fromAnotherStore.betaInstalled)
        assertFalse(row(installed = 6, betaVersion = null).betaInstalled)
    }

    @Test
    fun versionOfferedOrOlderIsNeither() {
        assertFalse(row(installed = 5, betaVersion = 8).newerInstalled)
        assertFalse(row(installed = 4, betaVersion = 8).betaInstalled)
        assertFalse(row(installed = null, betaVersion = 8).newerInstalled)
    }
}
