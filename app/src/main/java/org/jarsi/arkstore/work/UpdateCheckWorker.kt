package org.jarsi.arkstore.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.BatteryManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jarsi.arkstore.R
import org.jarsi.arkstore.data.CatalogRepository
import org.jarsi.arkstore.data.HttpStatusException
import org.jarsi.arkstore.data.InstalledApps
import org.jarsi.arkstore.data.InstalledVersion
import org.jarsi.arkstore.data.KeptVersions
import org.jarsi.arkstore.data.RateLimitedException
import org.jarsi.arkstore.data.StoreApp
import org.jarsi.arkstore.data.UpdatePolicy
import org.jarsi.arkstore.install.FailReason
import org.jarsi.arkstore.install.InstallManager
import org.jarsi.arkstore.install.Outcome
import org.jarsi.arkstore.install.Root
import org.jarsi.arkstore.install.RootCheck
import org.jarsi.arkstore.install.installOrder
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
 * are taken out: they are installed already.
 */
internal fun toAnnounce(updates: List<StoreApp>, settled: Set<String>): List<StoreApp> =
    updates.filter { it.fullName !in settled }

/**
 * Whether the background check asks the root app at all: only when there is an update [toTry]
 * and the [settings] would let it be installed unasked now, on a network that is [unmetered]
 * or not and [charging] or not, were root given. Asking makes some root apps show a note
 * every time.
 */
internal fun asksRoot(settings: AutoUpdate, toTry: List<StoreApp>, unmetered: Boolean, charging: Boolean): Boolean =
    toTry.isNotEmpty() && settings.allows(root = true, unmetered, charging)

/**
 * Whether an unasked install that failed for [reason] ends the run: when root was turned off
 * or refused, which the next install would meet as well.
 */
internal fun endsRun(reason: FailReason?): Boolean = reason == FailReason.NO_ROOT

/**
 * Whether a run's unasked installs may go on: as the [settings] say, with root given when the
 * run asked for it ([rootGiven]), turned on still ([rootOn]) and not refused since
 * ([rootRefused]), so that turning root off stops the downloads as turning the switch off does.
 * A refusal is counted before the outcome of the install it came at is told, so a run whose
 * wait for that outcome ran out just then stops all the same.
 */
internal fun unaskedAllowed(
    settings: AutoUpdate,
    rootGiven: Boolean,
    rootOn: Boolean,
    rootRefused: Boolean,
    unmetered: Boolean,
    charging: Boolean
): Boolean = settings.allows(rootGiven && rootOn && !rootRefused, unmetered, charging)

/**
 * Whether an unasked install that failed for [reason] is left alone until a newer version: not
 * when root was turned off or refused, which the user may change, nor when the download
 * failed, as a network that dropped or a server that did not answer does now and then. Neither
 * says anything of the version.
 */
internal fun failureLasts(reason: FailReason?): Boolean =
    reason != FailReason.NO_ROOT && reason != FailReason.DOWNLOAD

/**
 * The updates among [found] the store may install unasked: of the apps it installed itself,
 * [self] being its package, and not of one whose installed version is the one the user went
 * back to, by [kept] (see [KeptVersions]). Such an update is announced like any other.
 */
internal fun unasked(found: List<Pair<StoreApp, InstalledVersion>>, self: String, kept: Map<String, Long>): List<StoreApp> =
    found.filter { (app, installed) ->
        installed.installer == self && !KeptVersions.keeps(kept, app.packageName, installed.versionCode)
    }.map { it.first }

/** The [updates] worth trying unasked: those whose version has not [failed] that way before. */
internal fun toTry(updates: List<StoreApp>, failed: Set<String>): List<StoreApp> =
    updates.filter { announcement(it) !in failed }

/** The [updates] the user still wants, as the [policy] stands now: not skipped, not held. */
internal fun wanted(updates: List<StoreApp>, policy: UpdatePolicy): List<StoreApp> =
    updates.filter { !policy.skips(it) }

/** Those of [failed] that are still among the [updates] offered; the rest are forgotten. */
internal fun stillFailed(failed: Set<String>, updates: List<StoreApp>): Set<String> =
    updates.map(::announcement).filter { it in failed }.toSet()

/**
 * Runs [block] and, every [intervalMillis] meanwhile, asks whether it is still [allowed];
 * the first time it is not, [onDisallowed] is called, once. What [block] returns is returned.
 */
internal suspend fun <T> watching(
    allowed: () -> Boolean,
    intervalMillis: Long,
    onDisallowed: () -> Unit,
    block: suspend () -> T
): T = coroutineScope {
    val watcher = launch {
        while (true) {
            delay(intervalMillis)
            if (!allowed()) {
                onDisallowed()
                break
            }
        }
    }
    try {
        block()
    } finally {
        watcher.cancel()
    }
}

/**
 * Periodically refreshes the catalogue, installs the updates it may unasked (see
 * [AutoUpdate]) and tells the user of the rest.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val started = SystemClock.elapsedRealtime()
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
        val preferences = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val settled = installUnasked(found, started, preferences)
        // The user may have skipped or held one back while the installs ran.
        val updates = wanted(toAnnounce(found.map { it.first }, settled), UpdatePolicy.read(applicationContext))
        val waiting = updates.map(::announcement).toSet()
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
     * that the store itself installed, through root, one after the other, as long as the time
     * a run [started] at is given lasts. Returns the full names of those installed. One that
     * failed is announced like any other update, and not tried unasked again until a newer
     * version is offered, unless it was only the download that failed ([failureLasts]); its
     * failure is not left for the user to find on a screen, as they did not ask for the
     * install.
     */
    private suspend fun installUnasked(
        found: List<Pair<StoreApp, InstalledVersion>>,
        started: Long,
        preferences: SharedPreferences
    ): Set<String> {
        val context = applicationContext
        val settings = AutoUpdate.read(context)
        val updates = found.map { it.first }
        val failed = stillFailed(preferences.getStringSet(PREF_FAILED, null).orEmpty(), updates).toMutableSet()
        val owned = unasked(found, context.packageName, KeptVersions.read(context))
        if (!asksRoot(settings, toTry(owned, failed), unmetered(context), charging(context))) return emptySet()
        // Asked once a run: the root app may ask the user the first time, and the answer holds
        // for the run. A refusal later on, in the middle of an install, ends the run, and the
        // update is tried again on the next. Refusals are counted from before the question, so
        // that one while it is on its way counts, and one from before the run does not.
        val refusals = InstallManager.rootRefusals
        val root = rootReady(context)
        if (!settings.allows(root, unmetered(context), charging(context))) return emptySet()
        val settled = HashSet<String>()
        val deadline = started + INSTALL_BUDGET_MS
        fun allowed() = unaskedAllowed(
            AutoUpdate.read(context), root, Root.enabled(context), InstallManager.rootRefusedSince(refusals),
            unmetered(context), charging(context)
        )
        for (app in installOrder(toTry(owned, failed), context.packageName)) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            // The user may have turned the switch off, or left the Wi-Fi, during the last one.
            if (!allowed()) break
            // Or skipped this very update, or held its app back, since the list was made.
            if (UpdatePolicy.read(context).skips(app)) continue
            // Or gone back to an earlier version of the app: the list knows the one before that.
            // Asked here, to leave the app alone, and by the install itself once the app is
            // reserved for it and again before the file goes to root: the user's own install
            // may end, and note the version, at any moment before the reservation.
            fun notKept() = app.packageName !in KeptVersions.read(context) || stillOwned(context, app)
            if (!notKept()) continue
            // And they may do any of these during this one: a download that goes on regardless
            // is what the settings were there to prevent. An install already with the system
            // goes on. A download not done within its time is given up too, as nothing would
            // watch over it any longer; and so is one this run is stopped in the middle of.
            fun stillWanted() = allowed() && !UpdatePolicy.read(context).skips(app)
            // Only the attempt this run began is its to give up: one the user began, which
            // installAndAwait finds on its way and leaves alone, is not.
            var attempt: Int? = null
            fun giveUp() = attempt?.let { InstallManager.cancel(app.fullName, it) }
            val done = try {
                watching(::stillWanted, CONDITIONS_INTERVAL_MS, { giveUp() }) {
                    InstallManager.installAndAwait(context, app, minOf(left, INSTALL_TIMEOUT_MS), ::notKept) { attempt = it }
                }
            } catch (e: CancellationException) {
                giveUp()
                throw e
            }
            when (done?.outcome) {
                Outcome.INSTALLED, Outcome.SAVED -> settled += app.fullName
                Outcome.FAILED -> {
                    InstallManager.dismissFailure(app.fullName)
                    // Why it failed comes with the attempt's outcome: the state shown may have
                    // been dismissed, or taken by a new attempt, by now.
                    if (endsRun(done.reason)) break
                    if (failureLasts(done.reason)) failed += announcement(app)
                }
                // Given up at the last moment for a version gone back to: the others go on.
                Outcome.CANCELLED -> if (notKept()) break
                null -> giveUp()
            }
        }
        preferences.edit { putStringSet(PREF_FAILED, failed) }
        return settled
    }

    /** Whether [app]'s update may still be installed unasked, as the apps stand installed now. */
    private fun stillOwned(context: Context, app: StoreApp): Boolean {
        val now = InstalledApps.updates(context, listOf(app))
        return unasked(now, context.packageName, KeptVersions.read(context)).isNotEmpty()
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
        private const val PREF_FAILED = "failed_unasked"
        // A run is given ten minutes in all, the refresh included; what is not done in time
        // waits for the next.
        private const val INSTALL_BUDGET_MS = 7 * 60 * 1000L
        private const val INSTALL_TIMEOUT_MS = 3 * 60 * 1000L
        private const val CONDITIONS_INTERVAL_MS = 1000L

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

/** Whether root is turned on and the root app gives it now; asks the root app, which may ask the user. */
internal suspend fun rootReady(context: Context): Boolean =
    Root.enabled(context) && Root.check() is RootCheck.Granted
