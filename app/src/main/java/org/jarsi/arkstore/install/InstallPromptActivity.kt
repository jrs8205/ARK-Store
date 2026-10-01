package org.jarsi.arkstore.install

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Opens the system's install prompt for a download that finished while the store was not on
 * screen. It is what the "ready to install" notification starts, and shows nothing itself.
 *
 * Going through here rather than straight to the prompt lets the store know that the prompt
 * has been opened, so its own screen does not open it a second time. The prompt travels in
 * the notification's intent, so this also works after the process has been stopped, when
 * nothing else remembers the install. The activity is not exported: only the store's own
 * notification can start it, never another app with a prompt of its choosing.
 */
class InstallPromptActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = intent.getStringExtra(EXTRA_REPO)
        val prompt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PROMPT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PROMPT)
        }
        if (savedInstanceState == null && repo != null && prompt != null) {
            InstallManager.confirmations.remove(repo)
            try {
                startActivity(prompt)
            } catch (e: Exception) {
                Log.w(TAG, "Could not show the install prompt", e)
                InstallManager.onSessionResult(
                    applicationContext,
                    repo,
                    PackageInstaller.STATUS_FAILURE,
                    e.message
                )
            }
        }
        finish()
    }

    companion object {
        private const val TAG = "InstallPromptActivity"
        private const val EXTRA_REPO = "repo"
        private const val EXTRA_PROMPT = "prompt"

        fun intent(context: Context, repo: String, prompt: Intent): Intent =
            Intent(context, InstallPromptActivity::class.java)
                .putExtra(EXTRA_REPO, repo)
                .putExtra(EXTRA_PROMPT, prompt)
    }
}
