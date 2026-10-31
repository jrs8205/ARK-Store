package org.jarsi.arkstore.install

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class InstallConfirmation<T>(val repo: String, val value: T)

/**
 * Keeps confirmations until a foreground activity can present them, one at a time. The one
 * presented stays active until its session settles, whether the prompt has closed or not:
 * the system installs after the prompt closes, and a prompt opened over that install shows
 * as a dark screen that takes no touch until the install is done.
 *
 * A prompt left without an answer for a while is [park]ed: its confirmation is kept, but
 * presented again only when the user asks for it, with [resume], so that a prompt the user
 * put aside does not keep coming back by itself. The screen learns which these are from
 * [unanswered] and offers them again as a button.
 *
 * Each time a confirmation is presented is a presentation of its own, numbered by
 * [presentation]: a parking meant for one presentation, as by a timer set when its prompt
 * closed, leaves a later presentation of the same confirmation alone.
 */
internal class InstallConfirmationQueue<T> {
    private val waiting = linkedMapOf<String, T>()
    private val parked = linkedMapOf<String, T>()
    private var active: InstallConfirmation<T>? = null
    private var presentations = 0
    /** When the confirmation of each app being presented or parked was presented, see [presentedAt]. */
    private val presented = hashMapOf<String, Long>()
    private val _next = MutableStateFlow<InstallConfirmation<T>?>(null)
    val next = _next.asStateFlow()
    private val _unanswered = MutableStateFlow<Set<String>>(emptySet())
    /** The apps whose prompt was presented and left without an answer, oldest first. */
    val unanswered = _unanswered.asStateFlow()

    @Synchronized
    fun enqueue(repo: String, value: T) {
        if (active?.repo == repo) return
        // A new request supersedes one put aside: it is the one the system waits on now.
        parked.remove(repo)
        waiting[repo] = value
        publish()
    }

    @Synchronized
    fun take(): InstallConfirmation<T>? {
        if (active != null) return null
        val entry = waiting.entries.firstOrNull() ?: return null
        present(entry.key, entry.value)
        waiting.remove(entry.key)
        publish()
        return active
    }

    /**
     * Someone else, such as a notification, is presenting the confirmation of [repo]: it
     * becomes the active one, so that the screen does not present it a second time, and the
     * number of its presentation is returned. When another is active, the confirmation is
     * parked instead, as a prompt the screen must not present again once the other settles,
     * though the user may ask for it; then, as when the confirmation is not here, null. One
     * not here is presented all the same, by a notification of an earlier process, and is
     * remembered as presented, see [presentedAt], until its app is [remove]d.
     */
    @Synchronized
    fun hold(repo: String): Int? {
        if (active?.repo == repo) return presentations
        val value = waiting.remove(repo) ?: parked.remove(repo) ?: run {
            presented[repo] = System.currentTimeMillis()
            return null
        }
        if (active != null) {
            parked[repo] = value
            presented[repo] = System.currentTimeMillis()
            publish()
            return null
        }
        present(repo, value)
        publish()
        return presentations
    }

    /** The number of the latest presentation; each [take] or [hold] that presents a confirmation begins a new one. */
    @Synchronized
    fun presentation(): Int = presentations

    /**
     * When the confirmation of [repo] was presented, in [System.currentTimeMillis], while it is
     * being presented or is parked since, or was presented from elsewhere (see [hold]); null
     * while it waits to be presented, or nothing of it was presented.
     */
    @Synchronized
    fun presentedAt(repo: String): Long? = presented[repo]

    /** The confirmations nobody has presented yet, oldest first. */
    @Synchronized
    fun waiting(): List<InstallConfirmation<T>> =
        waiting.map { InstallConfirmation(it.key, it.value) }

    /** The confirmations presented and left without an answer, oldest first. */
    @Synchronized
    fun parked(): List<InstallConfirmation<T>> =
        parked.map { InstallConfirmation(it.key, it.value) }

    /** The confirmation being presented, if any. */
    @Synchronized
    fun active(): InstallConfirmation<T>? = active

    /** Whether a confirmation of [repo] is here in any form: waiting, parked or being presented. */
    @Synchronized
    fun holds(repo: String): Boolean =
        repo in waiting || repo in parked || active?.repo == repo

    /**
     * The session of [repo] has settled, one way or the other: its confirmation is no longer
     * needed, and the next may be presented if it was the active one.
     */
    @Synchronized
    fun remove(repo: String) {
        waiting.remove(repo)
        parked.remove(repo)
        presented.remove(repo)
        if (active?.repo == repo) active = null
        publish()
    }

    /**
     * The prompt of [repo] was left without an answer, while it is still the active one and
     * its [presentation] is still the one going on: a prompt the user put aside settles
     * nothing, and the rest must not wait forever. The confirmation is parked, to be
     * presented again when the user asks, as its session is still waiting for the answer.
     */
    @Synchronized
    fun park(repo: String, presentation: Int) {
        val open = active ?: return
        if (open.repo != repo || presentation != presentations) return
        active = null
        parked[repo] = open.value
        publish()
    }

    /**
     * The user asks for the parked prompt of [repo] again: it goes to the front of the line,
     * to be presented as soon as nothing else is. Returns whether there was one.
     */
    @Synchronized
    fun resume(repo: String): Boolean {
        val value = parked.remove(repo) ?: return false
        presented.remove(repo)
        val rest = LinkedHashMap(waiting)
        waiting.clear()
        waiting[repo] = value
        waiting.putAll(rest)
        publish()
        return true
    }

    private fun present(repo: String, value: T) {
        active = InstallConfirmation(repo, value)
        presentations++
        presented[repo] = System.currentTimeMillis()
    }

    private fun publish() {
        _next.value = if (active == null) {
            waiting.entries.firstOrNull()?.let { InstallConfirmation(it.key, it.value) }
        } else {
            null
        }
        _unanswered.value = parked.keys.toSet()
    }
}
