package org.jarsi.arkstore.install

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class InstallConfirmation<T>(val repo: String, val value: T)

/** Keeps confirmations until a foreground activity can present them, one at a time. */
internal class InstallConfirmationQueue<T> {
    private val waiting = linkedMapOf<String, T>()
    private var activeRepo: String? = null
    private val _next = MutableStateFlow<InstallConfirmation<T>?>(null)
    val next = _next.asStateFlow()

    @Synchronized
    fun enqueue(repo: String, value: T) {
        if (activeRepo == repo) return
        waiting[repo] = value
        publishNext()
    }

    @Synchronized
    fun take(): InstallConfirmation<T>? {
        if (activeRepo != null) return null
        val entry = waiting.entries.firstOrNull() ?: return null
        val confirmation = InstallConfirmation(entry.key, entry.value)
        waiting.remove(entry.key)
        activeRepo = confirmation.repo
        publishNext()
        return confirmation
    }

    @Synchronized
    fun finishActive() {
        activeRepo = null
        publishNext()
    }

    @Synchronized
    fun remove(repo: String) {
        waiting.remove(repo)
        // A session result can arrive before its confirmation activity has closed.
        publishNext()
    }

    private fun publishNext() {
        _next.value = if (activeRepo == null) {
            waiting.entries.firstOrNull()?.let { InstallConfirmation(it.key, it.value) }
        } else {
            null
        }
    }
}
