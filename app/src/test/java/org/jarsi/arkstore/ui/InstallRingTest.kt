package org.jarsi.arkstore.ui

import org.jarsi.arkstore.R
import org.jarsi.arkstore.install.InstallState
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallRingTest {
    @Test
    fun theRingTellsThePhaseApartFromTheProgress() {
        assertEquals(InstallRing(0f, cancellable = true, R.string.state_queued), ringOf(InstallState.Queued))
        assertEquals(InstallRing(0f, cancellable = true, R.string.state_downloading), ringOf(InstallState.Downloading(0f)))
        assertEquals(InstallRing(0.4f, cancellable = true, R.string.state_downloading), ringOf(InstallState.Downloading(0.4f)))
        // A download of unknown size spins, but is a download still, and can be cancelled.
        assertEquals(InstallRing(null, cancellable = true, R.string.state_downloading), ringOf(InstallState.Downloading(null)))
        assertEquals(InstallRing(null, cancellable = false, R.string.state_installing), ringOf(InstallState.Installing))
    }
}
