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
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jarsi.arkstore.R
import org.jarsi.arkstore.data.CatalogRepository
import org.jarsi.arkstore.ui.MainActivity

/**
 * Keeps downloads running when the user leaves the app. The work itself is done by
 * [InstallManager]; this foreground service only tells the system that the process is busy, so
 * it is not stopped halfway through a large file, and shows the progress in a notification.
 */
class InstallService : Service() {

    /** What the progress notification shows; it is posted again only when this changes. */
    internal data class Progress(val title: String, val percent: Int?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var lastStartId = 0

    private val open: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            MainActivity.openIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // A service started as a foreground service has to become one promptly, even if the
        // work has already finished by the time it gets here.
        val foreground = try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(progress(InstallManager.states.value)),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
            true
        } catch (e: Exception) {
            // The system refuses when the day's allowance for this kind of service is used up,
            // or when the app has gone to the background in the meantime.
            Log.w(TAG, "Not allowed to the foreground; downloads continue while the app is open", e)
            false
        }
        if (!foreground || InstallManager.activeJobs.value == 0) {
            stop()
        } else if (observer?.isActive != true) {
            // Started only once it is stored: on this dispatcher the body would otherwise run
            // at once, and a stop() from inside it would find nothing to cancel.
            val watching = scope.launch(start = CoroutineStart.LAZY) {
                launch {
                    InstallManager.activeJobs.first { it == 0 }
                    stop()
                }
                // Progress changes a hundred times per download. The system drops updates
                // that come too fast, and the last one must not be among those dropped.
                InstallManager.states.map(::progress).distinctUntilChanged().conflate().collect {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(it))
                    delay(UPDATE_INTERVAL_MS)
                }
            }
            observer = watching
            watching.start()
        }
        return START_NOT_STICKY
    }

    /** Android 15 and later limit how long this kind of service may run in a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground time used up; downloads continue while the app is open")
        stop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Stops showing progress and lets the service go. Another start may already be on its way
     * when this is called; stopping by the id of the last start received leaves the service
     * alive for that one, which then finds it still has to go to the foreground.
     */
    private fun stop() {
        observer?.cancel()
        observer = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    private fun progress(states: Map<String, InstallState>): Progress {
        val downloading = states.filterValues { it is InstallState.Downloading || it is InstallState.Queued }
        val single = downloading.entries.singleOrNull()
        val title = when {
            single != null -> getString(
                R.string.download_notification_one,
                CatalogRepository.get(this).titleOf(single.key)
            )
            downloading.size > 1 -> resources.getQuantityString(
                R.plurals.download_notification_many,
                downloading.size,
                downloading.size
            )
            else -> getString(R.string.download_notification_installing)
        }
        val fraction = (single?.value as? InstallState.Downloading)?.progress
        return Progress(title, fraction?.let { (it * 100).toInt() })
    }

    private fun notification(progress: Progress): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ark)
            .setContentTitle(progress.title)
            .setProgress(100, progress.percent ?: 0, progress.percent == null)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    companion object {
        private const val TAG = "InstallService"
        private const val CHANNEL_ID = "downloads"
        private const val READY_CHANNEL_ID = "installs"
        private const val NOTIFICATION_ID = 2
        private const val READY_NOTIFICATION_ID = 3
        private const val UPDATE_INTERVAL_MS = 500L

        fun createChannels(context: Context) {
            val downloads = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_downloads),
                // Progress is worth seeing but never worth a sound.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_downloads_description)
            }
            // An install that waits for the user is something they have to be told about, so
            // it has a channel of its own that can alert, apart from the quiet progress.
            val ready = NotificationChannel(
                READY_CHANNEL_ID,
                context.getString(R.string.channel_installs),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_installs_description)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannels(listOf(downloads, ready))
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

        /**
         * Tells the user that [repo] has been downloaded and waits for their go-ahead, which
         * the system asks for with [confirm]. Shown when the app is not on screen to ask
         * itself. Tapping it opens the prompt through [InstallPromptActivity].
         *
         * [sessionId] keeps the prompts of different installs apart: to the system, two
         * pending intents that differ only in their extras are one and the same.
         */
        fun showReady(context: Context, repo: String, sessionId: Int, confirm: Intent) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            val prompt = PendingIntent.getActivity(
                context,
                sessionId,
                InstallPromptActivity.intent(context, repo, confirm),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, READY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ark)
                .setContentTitle(
                    context.getString(
                        R.string.install_ready_title,
                        CatalogRepository.get(context).titleOf(repo)
                    )
                )
                .setContentText(context.getString(R.string.install_ready_text))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(prompt)
                .setAutoCancel(true)
                .build()
            try {
                manager.notify(repo, READY_NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // The permission was withdrawn between the check and the call.
            }
        }

        fun cancelReady(context: Context, repo: String) {
            NotificationManagerCompat.from(context).cancel(repo, READY_NOTIFICATION_ID)
        }
    }
}
