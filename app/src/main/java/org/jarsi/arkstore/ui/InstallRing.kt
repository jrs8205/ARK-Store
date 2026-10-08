package org.jarsi.arkstore.ui

import androidx.annotation.StringRes
import org.jarsi.arkstore.R
import org.jarsi.arkstore.install.InstallState

/**
 * How an install on its way is shown: the ring's [progress], 0..1 or null for one that
 * spins, whether a tap may cancel it, and what it is called.
 */
internal data class InstallRing(val progress: Float?, val cancellable: Boolean, @StringRes val label: Int)

/** The ring of [state]; the phase decides the look, not the progress figure. */
internal fun ringOf(state: InstallState): InstallRing = when (state) {
    InstallState.Queued -> InstallRing(0f, cancellable = true, R.string.state_queued)
    // A download of unknown size spins, but is a download still, and can be cancelled.
    is InstallState.Downloading -> InstallRing(state.progress, cancellable = true, R.string.state_downloading)
    InstallState.Installing, is InstallState.Failed -> InstallRing(null, cancellable = false, R.string.state_installing)
}
