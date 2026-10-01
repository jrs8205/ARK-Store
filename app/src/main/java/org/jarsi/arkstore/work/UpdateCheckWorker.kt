package org.jarsi.arkstore.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.jarsi.arkstore.R
import org.jarsi.arkstore.data.CatalogRepository
import org.jarsi.arkstore.data.InstalledApps
import org.jarsi.arkstore.ui.MainActivity

/** Periodically refreshes the catalogue and tells the user when updates are waiting. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val catalog = try {
            CatalogRepository.get(applicationContext).refresh(foreground = false)
        } catch (_: IOException) {
            return Result.retry()
        }
        val updates = InstalledApps.countUpdates(applicationContext, catalog.apps)
        if (updates.isEmpty()) {
            NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
        } else {
            notify(applicationContext, updates.map { it.repo })
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "update-check"
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 1
        private const val INTERVAL_HOURS = 4L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                INTERVAL_HOURS,
                TimeUnit.HOURS
            ).setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // UPDATE rather than KEEP, so that a changed interval reaches existing installs.
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        private fun notify(context: Context, names: List<String>) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ark)
                .setContentTitle(
                    context.resources.getQuantityString(
                        R.plurals.notification_updates_title,
                        names.size,
                        names.size
                    )
                )
                .setContentText(names.joinToString(", "))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
