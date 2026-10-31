package org.jarsi.arkstore.install

import org.json.JSONException
import org.json.JSONObject

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
    /** The record as it is stored. The label and the prompt may hold anything, line breaks included. */
    fun encode(): String = JSONObject()
        .put(SESSION, sessionId)
        .put(PACKAGE, packageName)
        .putOpt(LABEL, label)
        .put(PROMPT, prompt)
        .toString()

    companion object {
        private const val SESSION = "session"
        private const val PACKAGE = "package"
        private const val LABEL = "label"
        private const val PROMPT = "prompt"

        /** The record stored for [repo], or null when [text] is not one. */
        fun decode(repo: String, text: String?): PendingConfirmation? {
            val record = try {
                JSONObject(text ?: return null)
            } catch (e: JSONException) {
                return null
            }
            val sessionId = record.optString(SESSION).toIntOrNull() ?: return null
            if (record.optString(PACKAGE).isEmpty() || record.optString(PROMPT).isEmpty()) return null
            return PendingConfirmation(
                repo,
                sessionId,
                record.getString(PACKAGE),
                record.optString(LABEL).ifEmpty { null },
                record.getString(PROMPT)
            )
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
