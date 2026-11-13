package org.jarsi.arkstore.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The permissions a file asks for, as the lists write them beside the file, each by its name.
 * A name of the platform's own, which begins with "android.permission.", is written as what
 * follows that with the dot kept in front (".CAMERA"): most names are such, and a list has
 * thousands of files. A permission asked for only up to some version of Android has that
 * version's API level after an "@" (".WRITE_EXTERNAL_STORAGE@28"). A name as it is written
 * never begins with a dot and holds no "@", so the two ways of writing cannot be taken for
 * each other. The builder of the lists writes them by the same rules.
 */
object Permissions {
    private const val PLATFORM = "android.permission."

    /** The most a file's list holds, and the longest a name in it is. */
    const val MAX = 200
    const val MAX_NAME = 200

    /** No device has an Android beyond this; a highest version beyond it limits nothing. */
    private const val MAX_SDK = 10000
    private val NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.\\-]*")

    /** What follows an app's package in the name of the permission androidx makes for the app's own receivers. */
    private const val OWN_RECEIVERS = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    /**
     * One permission a file asks for: its whole [name], and the highest Android API level it
     * is asked for on, or null when it is asked for on every one.
     */
    data class Asked(val name: String, val maxSdk: Int? = null)

    /**
     * [asked] as a list writes them, in the order they come. A name asked for more than once
     * is written once, where it first came, with its widest reach. A name that is not written
     * the way names are is left out, since what a manifest says would otherwise go on as it
     * is, and of more than [MAX] only so many are kept.
     */
    fun listed(asked: List<Asked>): List<String> {
        val reach = LinkedHashMap<String, Int?>()
        for ((name, max) in asked) {
            if (name.length > MAX_NAME || !NAME.matches(name)) continue
            // Android takes 0 for no limit.
            val highest = max?.takeIf { it in 1..MAX_SDK }
            if (name !in reach) {
                if (reach.size < MAX) reach[name] = highest
            } else {
                val known = reach[name]
                if (known != null && (highest == null || highest > known)) reach[name] = highest
            }
        }
        return reach.map { (name, highest) ->
            val short = if (name.startsWith(PLATFORM) && name.length > PLATFORM.length) name.substring(PLATFORM.length - 1) else name
            if (highest == null) short else "$short@$highest"
        }
    }

    /** What [text], an entry of a file's list, stands for, or null when it is not written the way entries are. */
    fun asked(text: String): Asked? {
        val at = text.indexOf('@')
        val written = if (at < 0) text else text.substring(0, at)
        val maxSdk = if (at < 0) {
            null
        } else {
            text.substring(at + 1).takeIf { part -> part.isNotEmpty() && part.all { it in '0'..'9' } && part.length <= 5 }
                ?.toInt()?.takeIf { it in 1..MAX_SDK }
                ?: return null
        }
        // A dot with nothing after it names nothing of the platform's.
        if (written == "." || written.length > MAX_NAME) return null
        val name = if (written.startsWith(".")) PLATFORM + written.substring(1) else written
        if (!NAME.matches(name)) return null
        return Asked(name, maxSdk)
    }

    /**
     * The permissions [json], the entry of a file in a list or a stored app, tells of, as they
     * are written; null when it tells none, as an entry written before the permissions were
     * read, which is not the same as a file that asks for none. An entry not written the way
     * entries are is left out, and of more than [MAX] only so many are kept.
     */
    fun of(json: JSONObject): List<String>? {
        val array = json.opt("permissions") as? JSONArray ?: return null
        val listed = ArrayList<String>(minOf(array.length(), MAX))
        for (i in 0 until array.length()) {
            if (listed.size == MAX) break
            val text = array.opt(i) as? String ?: continue
            if (asked(text) != null) listed += text
        }
        return listed
    }

    /**
     * The whole names of those of [listed] a device of the Android API level [sdk] would be
     * asked for, in the order they are listed. The one permission the build tools make for
     * the app of [packageName] itself, so that its own receivers hear only the app, is left
     * out: it gives the app nothing of the device's or of another app's. Nothing else is
     * taken for the app's own by its name: a name under an app's package says nothing of who
     * made the permission, and it may guard another app's data.
     */
    fun onDevice(listed: List<String>, sdk: Int, packageName: String?): List<String> {
        val own = packageName?.let { it + OWN_RECEIVERS }
        return listed.mapNotNull(::asked)
            .filter { it.maxSdk == null || sdk <= it.maxSdk }
            .map { it.name }
            .filter { it != own }
            .distinct()
    }

    /** Those of [offered] that are not among [installed], the permissions the installed version asks for. */
    fun added(offered: List<String>, installed: Collection<String>): List<String> {
        val has = installed.toHashSet()
        return offered.filter { it !in has }
    }
}
