package org.jarsi.arkstore.data

/**
 * App categories, decided by the repository's GitHub topics. A developer files an app under a
 * category by adding the topic `arkstore-<category>` (for example `arkstore-tools`) to the
 * repository. Without one, a few widely used topics are recognised; everything else is "other".
 */
object Categories {
    const val OTHER = "other"
    const val TOPIC_PREFIX = "arkstore-"

    /** Category ids in the order they are offered. */
    val ALL = listOf(
        "communication",
        "productivity",
        "tools",
        "personalization",
        "media",
        "games",
        "travel",
        "news",
        "system",
        "health",
        "finance",
        "education",
        OTHER
    )

    private val KNOWN_TOPICS = mapOf(
        "dialer" to "communication",
        "sms" to "communication",
        "messaging" to "communication",
        "chat" to "communication",
        "email" to "communication",
        "notes" to "productivity",
        "todo" to "productivity",
        "calendar" to "productivity",
        "productivity" to "productivity",
        "launcher" to "personalization",
        "android-launcher" to "personalization",
        "keyboard" to "personalization",
        "ime" to "personalization",
        "wallpaper" to "personalization",
        "icon-pack" to "personalization",
        "music" to "media",
        "music-player" to "media",
        "video" to "media",
        "podcast" to "media",
        "camera" to "media",
        "gallery" to "media",
        "remote-control" to "media",
        "game" to "games",
        "games" to "games",
        "maps" to "travel",
        "navigation" to "travel",
        "public-transport" to "travel",
        "weather" to "news",
        "news" to "news",
        "rss" to "news",
        "system-monitor" to "system",
        "battery" to "system",
        "file-manager" to "system",
        "fitness" to "health",
        "health" to "health",
        "finance" to "finance",
        "budget" to "finance",
        "education" to "education",
        "learning" to "education",
        "utility" to "tools",
        "tools" to "tools",
        "calculator" to "tools"
    )

    fun of(topics: List<String>): String {
        val lower = topics.map { it.lowercase() }
        lower.firstNotNullOfOrNull { topic ->
            topic.removePrefix(TOPIC_PREFIX).takeIf { topic.startsWith(TOPIC_PREFIX) && it in ALL }
        }?.let { return it }
        return lower.firstNotNullOfOrNull { KNOWN_TOPICS[it] } ?: OTHER
    }
}
