package org.jarsi.arkstore.backup

import android.app.Notification
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
import org.jarsi.arkstore.install.InstallService
import org.jarsi.arkstore.ui.MainActivity

/**
 * Keeps a backup or a restore going when the user leaves the app. The work itself is done by
 * [BackupManager]; this foreground service only tells the system that the process is busy, so
 * that it is not stopped halfway through the files, and shows the progress in a notification.
 */
class BackupService : Service() {

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
                notification(progress(BackupManager.state.value)),
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
            Log.w(TAG, "Not allowed to the foreground; the backup continues while the app is open", e)
            false
        }
        if (!foreground || BackupManager.state.value !is BackupState.Running) {
            stop()
        } else if (observer?.isActive != true) {
            // Started only once it is stored: on this dispatcher the body would otherwise run
            // at once, and a stop() from inside it would find nothing to cancel.
            val watching = scope.launch(start = CoroutineStart.LAZY) {
                launch {
                    BackupManager.state.first { it !is BackupState.Running }
                    stop()
                }
                // The progress changes often. The system drops updates that come too fast, and
                // the last one must not be among those dropped.
                BackupManager.state.map(::progress).distinctUntilChanged().conflate().collect {
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
        Log.w(TAG, "Foreground time used up; the backup continues while the app is open")
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

    private fun progress(state: BackupState): Progress {
        val running = state as? BackupState.Running
            ?: return Progress(getString(R.string.backup_notification_saving, ""), null)
        val title = getString(
            if (running.restoring) R.string.backup_notification_restoring else R.string.backup_notification_saving,
            running.label
        )
        val percent = if (running.bytesTotal > 0) (running.bytesDone * 100 / running.bytesTotal).toInt() else null
        return Progress(title, percent)
    }

    private fun notification(progress: Progress): Notification =
        NotificationCompat.Builder(this, InstallService.CHANNEL_ID)
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
        private const val TAG = "BackupService"
        private const val NOTIFICATION_ID = 4
        private const val UPDATE_INTERVAL_MS = 500L

        /**
         * Starts the service for the work [BackupManager] has just taken on. The system may
         * refuse when the app is not in the foreground; the work then simply runs without it.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BackupService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the backup service", e)
            }
        }
    }
}
