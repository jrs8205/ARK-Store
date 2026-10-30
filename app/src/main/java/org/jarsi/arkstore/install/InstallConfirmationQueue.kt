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
 */
internal class InstallConfirmationQueue<T> {
    private val waiting = linkedMapOf<String, T>()
    private val parked = linkedMapOf<String, T>()
    private var active: InstallConfirmation<T>? = null
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
        val confirmation = InstallConfirmation(entry.key, entry.value)
        waiting.remove(entry.key)
        active = confirmation
        publish()
        return confirmation
    }

    /**
     * Someone else, such as a notification, is presenting the confirmation of [repo]: it
     * becomes the active one, so that the screen does not present it a second time. Returns
     * whether it was waiting or parked here; nothing changes when another is active.
     */
    @Synchronized
    fun hold(repo: String): Boolean {
        if (active != null) return active?.repo == repo
        val value = waiting.remove(repo) ?: parked.remove(repo) ?: return false
        active = InstallConfirmation(repo, value)
        publish()
        return true
    }

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
        if (active?.repo == repo) active = null
        publish()
    }

    /**
     * The prompt of [repo] was left without an answer, while it is still the active one and
     * its value [matches]: a prompt the user put aside settles nothing, and the rest must not
     * wait forever. The confirmation is parked, to be presented again when the user asks, as
     * its session is still waiting for the answer.
     */
    @Synchronized
    fun park(repo: String, matches: (T) -> Boolean) {
        val open = active ?: return
        if (open.repo != repo || !matches(open.value)) return
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
        val rest = LinkedHashMap(waiting)
        waiting.clear()
        waiting[repo] = value
        waiting.putAll(rest)
        publish()
        return true
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
