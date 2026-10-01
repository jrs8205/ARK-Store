package org.jarsi.arkstore.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.jarsi.arkstore.R
import org.jarsi.arkstore.ui.MainActivity

/**
 * Keeps downloads running when the user leaves the app. The work itself is done by
 * [InstallManager]; this foreground service only tells the system that the process is busy, so
 * it is not stopped halfway through a large file, and shows the progress in a notification.
 */
class InstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A service started as a foreground service has to become one promptly, even if the
        // work has already finished by the time it gets here.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(InstallManager.states.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
        if (!observing) {
            observing = true
            scope.launch {
                combine(InstallManager.activeJobs, InstallManager.states) { jobs, states ->
                    jobs to states
                }.collect { (jobs, states) ->
                    if (jobs == 0) {
                        ServiceCompat.stopForeground(this@InstallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification(states))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 and later limit how long this kind of service may run in a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground time used up; downloads continue while the app is open")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(states: Map<String, InstallState>): Notification {
        val downloading = states.filterValues { it is InstallState.Downloading }
        val single = downloading.entries.singleOrNull()
        val title = when {
            single != null -> getString(R.string.download_notification_one, single.key.substringAfter('/'))
            downloading.size > 1 -> resources.getQuantityString(
                R.plurals.download_notification_many,
                downloading.size,
                downloading.size
            )
            else -> getString(R.string.download_notification_installing)
        }
        val progress = (single?.value as? InstallState.Downloading)?.progress
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ark)
            .setContentTitle(title)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "InstallService"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_downloads),
                // Progress is worth seeing but never worth a sound.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_downloads_description)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        /**
         * Starts the service for the work [InstallManager] has just taken on. The system may
         * refuse when the app is not in the foreground; the work then simply runs without it.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, InstallService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the download service", e)
            }
        }
    }
}
