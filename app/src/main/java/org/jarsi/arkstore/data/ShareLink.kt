package org.jarsi.arkstore.data

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * A link to an app's page in the store: what sharing an app gives out, and what the store
 * reads when such a link is followed. It names the app by its [fullName], which also tells
 * the page behind the link where the app lives, for a reader who has no store to open it
 * with. The [packageName] goes along when it is known, so that the app is found on a device
 * that is offered it from another place.
 *
 * Anyone can write such a link, so one is read only when every part of it is what the store
 * itself would have written: an address to the store's own page, a name made of the letters
 * names are made of, and nothing given twice.
 */
data class ShareLink(val fullName: String, val packageName: String?) {

    /** The link as it is given out. Nothing in it needs escaping: see [isName]. */
    val address: String
        get() = "https://$HOST$PATH?$NAME=$fullName" + (packageName?.let { "&$PACKAGE=$it" } ?: "")

    companion object {
        /** The address the links are under; the manifest names the same one. */
        const val HOST = "arkstore.jrs8205.com"
        private const val PATH = "/app/"
        private const val NAME = "id"
        private const val PACKAGE = "p"

        private const val MAX_ADDRESS = 1000
        private const val MAX_NAME = 200
        private const val MAX_PARTS = 10
        private const val MAX_PACKAGE = 255

        private val PLACE = Regex("[a-z]{2,20}")
        private val PART = Regex("[A-Za-z0-9._-]+")
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

        /** The link to [app], or null when its name is not one a link can carry. */
        fun of(app: StoreApp): ShareLink? =
            if (isName(app.fullName)) ShareLink(app.fullName, app.packageName?.takeIf(::isPackage)) else null

        /**
         * The link [address] is, or null when it is not a link to an app's page. A package
         * that is not one is left out rather than taken as a reason to turn the link down:
         * the name alone finds the app.
         */
        fun parse(address: String): ShareLink? {
            if (address.length > MAX_ADDRESS) return null
            val uri = try {
                URI(address)
            } catch (_: URISyntaxException) {
                return null
            }
            if (!"https".equals(uri.scheme, ignoreCase = true)) return null
            if (!HOST.equals(uri.host, ignoreCase = true) || uri.rawUserInfo != null) return null
            if (uri.port != -1 && uri.port != 443) return null
            if (uri.rawPath != PATH && uri.rawPath != PATH.removeSuffix("/")) return null
            val given = HashMap<String, String>()
            for (part in uri.rawQuery.orEmpty().split('&')) {
                val name = part.substringBefore('=')
                if (name != NAME && name != PACKAGE) continue
                val value = decoded(part.substringAfter('=', "")) ?: return null
                if (given.put(name, value) != null) return null
            }
            val name = given[NAME]?.takeIf(::isName) ?: return null
            return ShareLink(name, given[PACKAGE]?.takeIf(::isPackage))
        }

        /**
         * The app among [apps] that [link] points to, or null when none of them is it: the
         * one under the name, as it is written or failing that in whatever case, and else the
         * one with the package, which is the same app offered from another place.
         */
        fun pick(apps: List<StoreApp>, link: ShareLink): StoreApp? =
            apps.firstOrNull { it.fullName == link.fullName }
                ?: apps.firstOrNull { it.fullName.equals(link.fullName, ignoreCase = true) }
                ?: link.packageName?.let { wanted -> apps.firstOrNull { it.packageName == wanted } }

        /**
         * Whether [name] is one a link carries: the path of a repository or the package of a
         * catalogue's app, after the name of the place when the place is not GitHub. Its parts
         * are made of letters, digits, dots, hyphens and underscores, none of which an address
         * escapes, and none of them is a step up or a standstill on a path.
         */
        private fun isName(name: String): Boolean {
            if (name.length > MAX_NAME) return false
            val path = if (':' in name) {
                if (!PLACE.matches(name.substringBefore(':'))) return false
                name.substringAfter(':')
            } else {
                name
            }
            val parts = path.split('/')
            return parts.size <= MAX_PARTS && parts.all { PART.matches(it) && it != "." && it != ".." }
        }

        private fun isPackage(name: String): Boolean =
            name.length <= MAX_PACKAGE && PACKAGE_NAME.matches(name)

        /** [value] with what an address escapes written out, or null when it is escaped wrong. */
        private fun decoded(value: String): String? = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * A [link] that was followed to the store and has not been shown yet. The app it names may
 * not be listed at the moment the link is followed: the lists are still being read on a
 * start, or are older than the app. So the link waits for the lists to be as fresh as they
 * get, which they are once [settledAt] refreshes have finished, before the app is told to be
 * missing; an app that is listed is shown without waiting.
 */
data class FollowedLink(val link: ShareLink, val settledAt: Int) {

    /** What the link has led to among [apps], the apps listed after [refreshes] finished refreshes. */
    fun leadsTo(apps: List<StoreApp>, refreshes: Int): Led {
        val app = ShareLink.pick(apps, link)
        return when {
            app != null -> Led.To(app)
            refreshes < settledAt -> Led.Nowhere
            else -> Led.Astray
        }
    }

    /** Where a followed link has led. */
    sealed interface Led {
        /** To [app], which is listed. */
        data class To(val app: StoreApp) : Led

        /** Nowhere yet: the app is not listed, and the lists are still being refreshed. */
        data object Nowhere : Led

        /** Astray: the lists are as fresh as they get, and the app is not in them. */
        data object Astray : Led
    }
}
