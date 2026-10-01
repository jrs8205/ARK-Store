package org.jarsi.arkstore.ui

import android.Manifest
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.jarsi.arkstore.install.InstallManager

class MainActivity : ComponentActivity() {

    private val viewModel: StoreViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val installConfirmation =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // PackageInstaller reports the installation outcome through InstallReceiver.
            InstallManager.confirmations.finishActive()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ArkStoreTheme {
                StoreScreen(viewModel)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                InstallManager.confirmations.next.collect {
                    val confirmation = InstallManager.confirmations.take() ?: return@collect
                    try {
                        installConfirmation.launch(confirmation.value)
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Could not show the install prompt", e)
                        InstallManager.onSessionResult(
                            confirmation.repo,
                            PackageInstaller.STATUS_FAILURE,
                            e.message
                        )
                        InstallManager.confirmations.finishActive()
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

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }
}
