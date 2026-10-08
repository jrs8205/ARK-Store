package org.jarsi.arkstore.install

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * One attempt to download and install an app, around its [job], numbered by [id] so that
 * whoever began it can tell it from a later attempt of the same app. A cancel and the
 * hand-over to the system exclude each other: once handed over, the attempt is the system's
 * and a cancel is refused; once cancelled, the hand-over is refused, so that a download
 * cancelled at its last moment is not committed after all.
 */
internal class InstallJob(val job: Job) {
    val id: Int = ids.incrementAndGet()
    private val lock = Any()
    private var handedOver = false

    fun start() = job.start()

    /** Cancels the attempt unless it has been handed over; returns whether it was. */
    fun cancel(): Boolean = synchronized(lock) {
        if (handedOver) return false
        job.cancel(CancellationException("cancelled"))
        true
    }

    /** Marks the attempt as the system's from here on, unless it has been cancelled. */
    @Throws(CancellationException::class)
    fun handOver() = synchronized(lock) {
        if (!job.isActive) throw CancellationException("cancelled")
        handedOver = true
    }

    /**
     * Calls [handler], once, when the attempt is over, with whether it was cancelled before
     * being handed over. Called also when the job never ran, as when it was cancelled before
     * its first turn, which is what makes it the place for the cleanup.
     */
    fun onSettled(handler: (cancelled: Boolean) -> Unit) {
        job.invokeOnCompletion { cause ->
            handler(cause is CancellationException && !synchronized(lock) { handedOver })
        }
    }

    private companion object {
        val ids = AtomicInteger(0)
    }
}
