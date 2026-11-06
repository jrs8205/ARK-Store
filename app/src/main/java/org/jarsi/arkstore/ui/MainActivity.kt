package org.jarsi.arkstore.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import org.jarsi.arkstore.install.ApkFolder
import org.jarsi.arkstore.install.InstallManager

class MainActivity : ComponentActivity() {

    private val viewModel: StoreViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The chosen theme's background from the first frame on, instead of the window's own.
        window.setBackgroundDrawable(windowBackground(this).toDrawable())
        setContent {
            ArkStoreTheme {
                StoreScreen(viewModel)
            }
        }
        if (savedInstanceState == null) openFolderIfAsked(intent)
        if (savedInstanceState == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFolderIfAsked(intent)
    }

    /** A notification of a saved file asks for the folder to be opened; see [openFolderIntent]. */
    private fun openFolderIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_FOLDER, false) != true) return
        // Taken off, so that turning the screen does not open it again.
        intent.removeExtra(EXTRA_OPEN_FOLDER)
        ApkFolder.open(this)
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
        InstallManager.onScreenStopped()
        super.onStop()
    }

    companion object {
        private const val EXTRA_OPEN_FOLDER = "org.jarsi.arkstore.OPEN_FOLDER"

        /**
         * The intent with which a notification brings the store forward. It is the launcher's
         * own, so a store that is already open is shown as it is, rather than getting a second
         * copy of this activity on top.
         */
        fun openIntent(context: Context): Intent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** The intent with which the notification of a saved file opens the folder, through the store. */
        fun openFolderIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_FOLDER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
