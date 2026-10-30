package org.jarsi.arkstore.install

/**
 * A confirmation the system asked for, written down so that it outlives the process: the
 * download is done and the system holds the files, waiting for the user's go-ahead, and the
 * store may well be gone from memory by the time the user comes back to it. With the record,
 * the store asks again instead of downloading again. The prompt is kept in its URI form.
 */
internal data class PendingConfirmation(
    val repo: String,
    val sessionId: Int,
    val packageName: String,
    val label: String?,
    val prompt: String
) {
    /** The record as it is stored: one line per field, the prompt last, as it may hold anything. */
    fun encode(): String =
        listOf(sessionId.toString(), packageName, label.orEmpty(), prompt).joinToString("\n")

    companion object {
        /** The record stored for [repo], or null when [text] is not one. */
        fun decode(repo: String, text: String?): PendingConfirmation? {
            val parts = text?.split("\n", limit = 4) ?: return null
            if (parts.size != 4) return null
            val sessionId = parts[0].toIntOrNull() ?: return null
            if (parts[1].isEmpty() || parts[3].isEmpty()) return null
            return PendingConfirmation(repo, sessionId, parts[1], parts[2].ifEmpty { null }, parts[3])
        }
    }
}

/**
 * The sessions among those [alive] in the system, each with the time it was made, that
 * nothing here [knows] of and that are at least [minAgeMillis] old at [now]. They are left
 * over from a process that ended before they settled, and only take up space. A session made
 * a moment ago is left alone: it may be one this process is still writing down.
 */
internal fun leftoverSessions(alive: Map<Int, Long>, known: Set<Int>, now: Long, minAgeMillis: Long): Set<Int> =
    alive.filter { (id, created) -> id !in known && now - created >= minAgeMillis }.keys
