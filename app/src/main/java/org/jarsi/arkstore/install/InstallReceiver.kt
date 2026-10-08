package org.jarsi.arkstore.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives the outcome of install sessions committed by [InstallManager]. */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val repo = intent.getStringExtra(EXTRA_REPO) ?: return
        val appContext = context.applicationContext
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null) {
                // The system puts the session's id on every status it reports.
                val sessionId =
                    intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, repo.hashCode())
                InstallManager.onConfirmationRequired(appContext, repo, sessionId, confirm)
            } else {
                InstallManager.onSessionResult(
                    appContext,
                    repo,
                    PackageInstaller.STATUS_FAILURE,
                    null
                )
            }
            return
        }

        InstallManager.onSessionResult(
            appContext,
            repo,
            status,
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1).takeIf { it >= 0 }
        )
    }

    companion object {
        const val ACTION_STATUS = "org.jarsi.arkstore.action.INSTALL_STATUS"
        const val EXTRA_REPO = "repo"
    }
}
