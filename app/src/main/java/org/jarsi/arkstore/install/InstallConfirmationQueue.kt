package org.jarsi.arkstore.install

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class InstallConfirmation<T>(val repo: String, val value: T)

/**
 * Keeps confirmations until a foreground activity can present them, one at a time. The one
 * presented stays active until its session settles, whether the prompt has closed or not:
 * the system installs after the prompt closes, and a prompt opened over that install shows
 * as a dark screen that takes no touch until the install is done.
 */
internal class InstallConfirmationQueue<T> {
    private val waiting = linkedMapOf<String, T>()
    private var active: InstallConfirmation<T>? = null
    private val _next = MutableStateFlow<InstallConfirmation<T>?>(null)
    val next = _next.asStateFlow()

    @Synchronized
    fun enqueue(repo: String, value: T) {
        if (active?.repo == repo) return
        waiting[repo] = value
        publishNext()
    }

    @Synchronized
    fun take(): InstallConfirmation<T>? {
        if (active != null) return null
        val entry = waiting.entries.firstOrNull() ?: return null
        val confirmation = InstallConfirmation(entry.key, entry.value)
        waiting.remove(entry.key)
        active = confirmation
        publishNext()
        return confirmation
    }

    /** The confirmations nobody has presented yet, oldest first. */
    @Synchronized
    fun waiting(): List<InstallConfirmation<T>> =
        waiting.map { InstallConfirmation(it.key, it.value) }

    /**
     * The session of [repo] has settled, one way or the other: its confirmation is no longer
     * needed, and the next may be presented if it was the active one.
     */
    @Synchronized
    fun remove(repo: String) {
        waiting.remove(repo)
        if (active?.repo == repo) active = null
        publishNext()
    }

    /**
     * Gives up waiting for the session of [repo], when it is still the active one and its
     * value [matches]: a prompt the user left without answering settles nothing, and the
     * rest must not wait forever. The confirmation goes to the back of the line, to be asked
     * again after the others, as its session is still waiting for the answer.
     */
    @Synchronized
    fun abandon(repo: String, matches: (T) -> Boolean) {
        val open = active ?: return
        if (open.repo != repo || !matches(open.value)) return
        active = null
        waiting[repo] = open.value
        publishNext()
    }

    private fun publishNext() {
        _next.value = if (active == null) {
            waiting.entries.firstOrNull()?.let { InstallConfirmation(it.key, it.value) }
        } else {
            null
        }
    }
}
