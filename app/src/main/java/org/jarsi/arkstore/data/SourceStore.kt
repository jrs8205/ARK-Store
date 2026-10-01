package org.jarsi.arkstore.data

import android.content.Context
import androidx.core.content.edit
import org.jarsi.arkstore.BuildConfig
import org.json.JSONArray

/**
 * The GitHub accounts and repositories that make up the catalogue. A source is either an
 * account ("owner"), which contributes all of its public repositories, or a single repository
 * ("owner/repo").
 */
class SourceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("sources", Context.MODE_PRIVATE)

    fun list(): List<String> {
        val stored = prefs.getString(KEY, null) ?: return listOf(BuildConfig.GITHUB_OWNER)
        val array = JSONArray(stored)
        return List(array.length()) { array.getString(it) }
    }

    /** Returns false when the source was already there. */
    fun add(source: String): Boolean {
        val current = list()
        if (current.any { it.equals(source, ignoreCase = true) }) return false
        save(current + source)
        return true
    }

    fun remove(source: String) = save(list() - source)

    /** Replaces [source] with [renamed], the name its repository goes by now. */
    fun rename(source: String, renamed: String) = save(renamed(list(), source, renamed))

    private fun save(sources: List<String>) = prefs.edit {
        putString(KEY, JSONArray(sources).toString())
    }

    companion object {
        private const val KEY = "sources"
        private val PATTERN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:/[A-Za-z0-9._-]+)?$")

        /**
         * Normalises what the user typed or pasted ("owner", "owner/repo" or a github.com
         * link) into a source, or returns null when it is neither.
         */
        fun parse(input: String): String? {
            val path = input.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .removePrefix("github.com/")
                .substringBefore('?')
                .substringBefore('#')
                .trim('/')
            val parts = path.split('/').filter { it.isNotEmpty() }.take(2)
            val source = parts.joinToString("/").removeSuffix(".git")
            return source.takeIf { PATTERN.matches(it) }
        }

        fun isRepository(source: String): Boolean = '/' in source

        /**
         * [sources] with [source] replaced by [renamed] in the same place, or simply dropped
         * when the new name is already among them.
         */
        internal fun renamed(sources: List<String>, source: String, renamed: String): List<String> {
            val others = sources.filter { it != source }
            if (others.any { it.equals(renamed, ignoreCase = true) }) return others
            return sources.map { if (it == source) renamed else it }
        }
    }
}
