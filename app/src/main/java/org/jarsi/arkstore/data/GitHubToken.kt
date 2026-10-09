package org.jarsi.arkstore.data

import android.content.Context
import androidx.core.content.edit
import java.net.URI
import java.net.URISyntaxException
import org.json.JSONException
import org.json.JSONObject

/**
 * The personal access token the user has given for GitHub, which raises the hourly limit of
 * API requests from 60 for the whole network to 5000 for the token. It is kept in
 * preferences of its own, left out of backups and device transfers (see the backup rules in
 * the manifest) and of what the store exports, and it is never logged. It is sent to
 * GitHub's API alone, see [authorization].
 */
object GitHubToken {
    const val PREFS = "github"
    private const val PREF_TOKEN = "token"
    private const val PREF_LIMIT = "limit"
    private const val API_HOST = "api.github.com"

    @Volatile
    private var current: String? = null

    /** Reads the token kept on the device; called once as the app starts. */
    fun load(context: Context) {
        current = preferences(context).getString(PREF_TOKEN, null)
    }

    fun get(): String? = current

    /** The hourly limit GitHub told for the token when it was saved, or null without one. */
    fun limit(context: Context): Int? =
        preferences(context).getInt(PREF_LIMIT, 0).takeIf { it > 0 && current != null }

    fun set(context: Context, token: String, limit: Int) {
        preferences(context).edit {
            putString(PREF_TOKEN, token)
            putInt(PREF_LIMIT, limit)
        }
        current = token
    }

    fun clear(context: Context) {
        preferences(context).edit { clear() }
        current = null
    }

    /**
     * The Authorization header to send with a request for [url] when [token] is held, or
     * null when none is to be sent: only a request to GitHub's API over HTTPS carries the
     * token, never a download, a raw file or anything a redirect leads to elsewhere.
     */
    fun authorization(url: String, token: String?): String? {
        if (token == null) return null
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        return if (uri.scheme == "https" && uri.host == API_HOST) "Bearer $token" else null
    }

    /** What the user typed, as a token: without the space around it, or null when it is none. */
    fun tidy(text: String): String? = text.trim().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }

    /** The hourly limit of API requests the answer to /rate_limit tells, or null when it does not. */
    fun limitOf(json: String): Int? = try {
        JSONObject(json).optJSONObject("resources")?.optJSONObject("core")?.optInt("limit")?.takeIf { it > 0 }
    } catch (_: JSONException) {
        null
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
