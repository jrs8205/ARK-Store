package org.jarsi.arkstore.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
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
import org.jarsi.arkstore.data.HttpStatusException
import org.jarsi.arkstore.data.InstalledApps
import org.jarsi.arkstore.data.InstalledVersion
import org.jarsi.arkstore.data.RateLimitedException
import org.jarsi.arkstore.data.StoreApp
import org.jarsi.arkstore.install.InstallManager
import org.jarsi.arkstore.install.Outcome
import org.jarsi.arkstore.ui.MainActivity

/** A request that timed out, or too many of them. */
private val PASSING_CODES = setOf(408, 429)

/**
 * Whether trying again soon is pointless: a used-up quota lasts until the hour is out, and a
 * source that no longer exists or that GitHub has blocked stays that way. The next scheduled
 * run looks again. A server error passes, and so does GitHub asking to slow down, which it
 * does by saying when to come back.
 */
internal fun isLasting(failure: IOException): Boolean = when (failure) {
    is RateLimitedException -> true
    is HttpStatusException ->
        failure.code in 400..499 && failure.code !in PASSING_CODES && !failure.retryAfter
    else -> false
}

/**
 * What identifies one waiting update: the repository's full name, since repositories of
 * different owners can share a name, and the version offered.
 */
internal fun announcement(app: StoreApp): String = "${app.fullName.lowercase()}@${app.versionCode}"

/** Whether [waiting] holds an update that is not among those already [announced]. */
internal fun newToAnnounce(waiting: Set<String>, announced: Set<String>): Boolean =
    (waiting - announced).isNotEmpty()

/**
 * The [updates] left to tell the user of once those [settled] unasked, by their full names,
 * are taken out: installed, or waiting for the user behind a notification of their own.
 */
internal fun toAnnounce(updates: List<StoreApp>, settled: Set<String>): List<StoreApp> =
    updates.filter { it.fullName !in settled }

/**
 * Periodically refreshes the catalogue, installs the updates it may unasked (see
 * [AutoUpdate]) and tells the user of the rest.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = CatalogRepository.get(applicationContext)
        val failure = try {
            repository.refresh(foreground = false)
            null
        } catch (e: IOException) {
            e
        }
        // A refresh that failed for one source has still updated the others, so whatever the
        // catalogue holds now is worth acting on.
        val found = InstalledApps.updates(applicationContext, repository.catalog.value.apps)
        val settled = installUnasked(found)
        val updates = toAnnounce(found.map { it.first }, settled)
        val waiting = updates.map(::announcement).toSet()
        val preferences = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val announced = preferences.getStringSet(PREF_ANNOUNCED, null).orEmpty()
        // Each update is announced once. A check that fails is retried, and every later check
        // finds the same updates again; a notification the user has dismissed must not come
        // back because of that.
        if (newToAnnounce(waiting, announced)) {
            notify(applicationContext, updates.map { it.title })
        } else if (waiting.isEmpty() && failure == null) {
            NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_ID)
        }
        preferences.edit { putStringSet(PREF_ANNOUNCED, waiting) }
        return if (failure == null || isLasting(failure)) Result.success() else Result.retry()
    }

    /**
     * Installs, without asking, the updates among [found] that the settings allow now and
     * that the store itself installed, one after the other, as long as the time a run is
     * given lasts. Returns the full names of those settled: installed, or handed to the
     * user to confirm. One that failed is announced like any other update.
     */
    private suspend fun installUnasked(found: List<Pair<StoreApp, InstalledVersion>>): Set<String> {
        val context = applicationContext
        val settings = AutoUpdate.read(context)
        if (!settings.allows(Build.VERSION.SDK_INT, unmetered(context), charging(context))) return emptySet()
        val settled = HashSet<String>()
        val deadline = SystemClock.elapsedRealtime() + INSTALL_BUDGET_MS
        for ((app, installed) in found) {
            if (installed.installer != context.packageName) continue
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            when (InstallManager.installAndAwait(context, app, minOf(left, INSTALL_TIMEOUT_MS))) {
                Outcome.INSTALLED, Outcome.CONFIRMATION_NEEDED -> settled += app.fullName
                Outcome.FAILED, null -> {}
            }
        }
        return settled
    }

    private fun unmetered(context: Context): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false

    private fun charging(context: Context): Boolean =
        context.getSystemService(BatteryManager::class.java)?.isCharging == true

    companion object {
        private const val WORK_NAME = "update-check"
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 1
        private const val INTERVAL_HOURS = 4L
        private const val PREFS = "updates"
        private const val PREF_ANNOUNCED = "announced"
        // A run is given ten minutes; what is not done in time waits for the next.
        private const val INSTALL_BUDGET_MS = 6 * 60 * 1000L
        private const val INSTALL_TIMEOUT_MS = 3 * 60 * 1000L

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
            ).apply {
                description = context.getString(R.string.channel_updates_description)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        /**
         * Whether an update notification would actually be shown. This covers the runtime
         * permission of Android 13 and later, the app-wide switch of earlier versions and
         * the switch of the update channel itself.
         */
        fun notificationsEnabled(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return true
            return channel.importance != NotificationManager.IMPORTANCE_NONE
        }

        private fun notify(context: Context, names: List<String>) {
            if (!notificationsEnabled(context)) return
            val open = PendingIntent.getActivity(
                context,
                0,
                MainActivity.openIntent(context),
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
                .setStyle(NotificationCompat.BigTextStyle().bigText(names.joinToString(", ")))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // The permission was withdrawn between the check and the call.
            }
        }
    }
}
