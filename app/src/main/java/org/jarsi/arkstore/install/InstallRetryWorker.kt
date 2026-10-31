package org.jarsi.arkstore.install

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.jarsi.arkstore.R

/**
 * Hands over once more an install the system gave up before asking, see [InstallManager.retry],
 * while no screen of the store is showing. Copying the files can take longer than a receiver
 * is allowed, and with nothing else running the process could be ended halfway through.
 */
class InstallRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        inputData.getString(KEY_REPO)?.let { InstallManager.retry(applicationContext, it) }
        return Result.success()
    }

    /** Asked for only before Android 12, where work that cannot wait runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val title = applicationContext.getString(R.string.download_notification_installing)
        return ForegroundInfo(
            NOTIFICATION_ID,
            InstallService.progressNotification(applicationContext, InstallService.Progress(title, null))
        )
    }

    companion object {
        private const val KEY_REPO = "repo"
        private const val NOTIFICATION_ID = 5

        fun enqueue(context: Context, repo: String) {
            val request = OneTimeWorkRequestBuilder<InstallRetryWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_REPO to repo))
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
