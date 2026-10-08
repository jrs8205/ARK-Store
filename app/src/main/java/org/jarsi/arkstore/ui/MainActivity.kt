package org.jarsi.arkstore.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.jarsi.arkstore.install.InstallManager
import org.jarsi.arkstore.install.InstallService

class MainActivity : ComponentActivity() {

    private val viewModel: StoreViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /**
     * Whose prompt is open, and of which session, so that its closing can be told to the
     * confirmations; kept across a recreation of the activity, which the result survives.
     */
    private var promptedRepo: String? = null
    private var promptedSession: Int = -1

    private val installConfirmation =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // PackageInstaller reports the installation outcome through InstallReceiver.
            promptedRepo?.let { InstallManager.onPromptClosed(it, promptedSession) }
            promptedRepo = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        promptedRepo = savedInstanceState?.getString(STATE_PROMPTED_REPO)
        promptedSession = savedInstanceState?.getInt(STATE_PROMPTED_SESSION, -1) ?: -1
        // The chosen theme's background from the first frame on, instead of the window's own.
        window.setBackgroundDrawable(windowBackground(this).toDrawable())
        setContent {
            ArkStoreTheme {
                StoreScreen(viewModel)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                InstallManager.confirmations.next.collect {
                    val confirmation = InstallManager.confirmations.take() ?: return@collect
                    InstallService.cancelReady(this@MainActivity, confirmation.repo)
                    try {
                        promptedRepo = confirmation.repo
                        promptedSession = confirmation.value.sessionId
                        installConfirmation.launch(confirmation.value.prompt)
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Could not show the install prompt", e)
                        promptedRepo = null
                        // A failed session releases its confirmation.
                        InstallManager.onSessionResult(
                            applicationContext,
                            confirmation.repo,
                            PackageInstaller.STATUS_FAILURE,
                            e.message
                        )
                    }
                }
            }
        }
        if (savedInstanceState == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PROMPTED_REPO, promptedRepo)
        outState.putInt(STATE_PROMPTED_SESSION, promptedSession)
    }

    override fun onStart() {
        super.onStart()
        InstallManager.onScreenStarted(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onStop() {
        InstallManager.onScreenStopped(applicationContext)
        super.onStop()
    }

    companion object {
        private const val STATE_PROMPTED_REPO = "prompted_repo"
        private const val STATE_PROMPTED_SESSION = "prompted_session"

        /**
         * The intent with which a notification brings the store forward. It is the launcher's
         * own, so a store that is already open is shown as it is, rather than getting a second
         * copy of this activity on top.
         */
        fun openIntent(context: Context): Intent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
