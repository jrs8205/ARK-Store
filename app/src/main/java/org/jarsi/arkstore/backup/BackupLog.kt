package org.jarsi.arkstore.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One backup or restore, as the settings tell of it afterwards: when, to or from where, how
 * many apps of what size, and how it went.
 */
data class BackupEvent(
    val kind: Kind,
    /** When the job ended, in milliseconds since the epoch. */
    val at: Long,
    /** The folder's or the zip file's name, as the device shows it. */
    val place: String,
    /** The apps the job was asked to take, and their size. */
    val apps: Int,
    val bytes: Long,
    /** Copied, or handed to the system for a restore. */
    val saved: Int,
    /** Found up to date in the folder. */
    val skipped: Int,
    val failed: Int,
    val outcome: Outcome
) {
    enum class Kind { FOLDER, ZIP, RESTORE }

    enum class Outcome { DONE, CANCELLED, FAILED }

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("at", at)
        .put("place", place)
        .put("apps", apps)
        .put("bytes", bytes)
        .put("saved", saved)
        .put("skipped", skipped)
        .put("failed", failed)
        .put("outcome", outcome.name)
}

/** The last backups and restores, newest first, kept on the device for the settings to tell. */
object BackupLog {
    const val MAX = 10

    fun added(events: List<BackupEvent>, event: BackupEvent): List<BackupEvent> = (listOf(event) + events).take(MAX)

    fun toText(events: List<BackupEvent>): String = JSONArray(events.map { it.toJson() }).toString()

    /** The events [text] holds; one that cannot be read is left out, and text that is no log holds none. */
    fun fromText(text: String?): List<BackupEvent> {
        val array = try {
            JSONArray(text ?: return emptyList())
        } catch (_: JSONException) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            val json = array.opt(i) as? JSONObject ?: return@mapNotNull null
            try {
                BackupEvent(
                    kind = BackupEvent.Kind.valueOf(json.getString("kind")),
                    at = json.getLong("at"),
                    place = json.getString("place").take(AppBackup.MAX_LABEL),
                    apps = json.getInt("apps"),
                    bytes = json.getLong("bytes"),
                    saved = json.getInt("saved"),
                    skipped = json.getInt("skipped"),
                    failed = json.getInt("failed"),
                    outcome = BackupEvent.Outcome.valueOf(json.getString("outcome"))
                )
            } catch (_: JSONException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }.take(MAX)
    }

    /** The event of a job of [kind] that took [apps] apps of [bytes] to or from [place] and ended as [state]. */
    fun event(kind: BackupEvent.Kind, at: Long, place: String, apps: Int, bytes: Long, state: BackupState): BackupEvent =
        when (state) {
            is BackupState.Done -> BackupEvent(
                kind, at, place, apps, bytes, state.saved, state.skipped, state.failed.size, BackupEvent.Outcome.DONE
            )
            is BackupState.Failed -> BackupEvent(
                kind, at, place, apps, bytes, 0, 0, 0,
                if (state.reason == BackupError.CANCELLED) BackupEvent.Outcome.CANCELLED else BackupEvent.Outcome.FAILED
            )
            else -> BackupEvent(kind, at, place, apps, bytes, 0, 0, 0, BackupEvent.Outcome.FAILED)
        }
}
