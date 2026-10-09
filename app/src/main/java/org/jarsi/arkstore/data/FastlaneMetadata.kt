package org.jarsi.arkstore.data

/**
 * Reads what a repository publishes of its app in the fastlane layout, the way the index
 * does for the apps it lists (see `metadata_from_paths` in `tools/build_index.py`), for a
 * source added on this device, which the index does not cover. Only the device's languages
 * and English are read, as each short description is a request.
 */
object FastlaneMetadata {
    const val FOLDER = "fastlane/metadata/android/"
    private const val MAX_SCREENSHOTS = 8
    private const val MAX_SUMMARY = 200
    /** How many folders of a language are tried when the first turns out empty. */
    private const val MAX_FOLDERS_TRIED = 3
    private val IMAGE_SUFFIXES = listOf(".png", ".webp", ".jpg", ".jpeg")
    private val DEFAULT_REGIONS = mapOf(
        "en" to "US", "fi" to "FI", "de" to "DE", "es" to "ES", "fr" to "FR", "it" to "IT", "pt" to "BR",
        "ru" to "RU", "uk" to "UA", "pl" to "PL", "nl" to "NL", "tr" to "TR", "zh" to "CN", "ja" to "JP",
        "ko" to "KR", "ar" to "SA", "hi" to "IN"
    )

    /** What a read gave, and whether every text it went for was read; see [read]. */
    data class Read(val metadata: Map<String, AppMetadata>, val complete: Boolean)

    /**
     * The metadata, by language, that the files at [paths] hold for [languages] and English:
     * the short description read with [readText] from its [address] (null when it cannot be
     * read, which costs the folder only its summary, and the read its completeness), the
     * address of the long description and the addresses of the phone screenshots, all from
     * one folder of the language.
     */
    fun read(
        paths: List<String>,
        languages: List<String>,
        address: (String) -> String,
        readText: (String) -> String?
    ): Read {
        var complete = true
        val reading = { text: String ->
            readText(text).also { if (it == null) complete = false }
        }
        return Read(readAll(paths, languages, address, reading), complete)
    }

    /**
     * The mark of a read for the push [stamp] of a repository, for [languages]: a read done
     * here covers the device's languages and English only, so the mark says which, and a
     * device that reads other languages reads again.
     */
    fun mark(stamp: String, languages: List<String>): String =
        stamp + MARK_SEPARATOR + read(languages).joinToString(",")

    /**
     * Whether [app]'s metadata, by its mark, covers the push [stamp] for [languages]: read
     * for that push, either for every language, as the index reads and marks with the push
     * alone, or for these languages here.
     */
    fun covers(app: StoreApp, stamp: String, languages: List<String>): Boolean {
        val mark = app.metadataAt ?: return false
        if (mark == stamp) return true
        val at = mark.indexOf(MARK_SEPARATOR)
        if (at < 0 || mark.substring(0, at) != stamp) return false
        val done = mark.substring(at + 1).split(',').toSet()
        return read(languages).all { it in done }
    }

    private const val MARK_SEPARATOR = '#'

    /** The languages a read for [languages] goes for: those and English, once each. */
    private fun read(languages: List<String>): List<String> = (languages + "en").distinct()

    private fun readAll(
        paths: List<String>,
        languages: List<String>,
        address: (String) -> String,
        readText: (String) -> String?
    ): Map<String, AppMetadata> {
        val summaries = HashMap<String, String>()
        val descriptions = HashMap<String, String>()
        val screenshots = HashMap<String, MutableList<String>>()
        for (path in paths) {
            if (!path.startsWith(FOLDER)) continue
            val parts = path.removePrefix(FOLDER).split('/')
            when {
                parts.size == 2 && parts[1] == "short_description.txt" -> summaries[parts[0]] = path
                parts.size == 2 && parts[1] == "full_description.txt" -> descriptions[parts[0]] = path
                parts.size == 4 && parts[1] == "images" && parts[2] == "phoneScreenshots" &&
                    IMAGE_SUFFIXES.any { path.lowercase().endsWith(it) } ->
                    screenshots.getOrPut(parts[0]) { ArrayList() }.add(path)
            }
        }
        val locales = summaries.keys + descriptions.keys + screenshots.keys
        val metadata = LinkedHashMap<String, AppMetadata>()
        for (language in read(languages)) {
            for (locale in candidates(language, locales).take(MAX_FOLDERS_TRIED)) {
                val summary = summaries[locale]?.let { readText(address(it)) }?.let(::summaryText)?.takeIf { it.isNotEmpty() }
                val description = descriptions[locale]?.let(address)
                val shown = screenshots[locale].orEmpty().sortedWith(NATURAL).take(MAX_SCREENSHOTS).map(address)
                if (summary != null || description != null || shown.isNotEmpty()) {
                    metadata[language] = AppMetadata(description, shown, summary)
                    break
                }
            }
        }
        return metadata
    }

    /**
     * The folders among [locales] to read [language] from, in order of preference: the one of
     * the language's usual region, the language alone, then its other regions in order.
     */
    internal fun candidates(language: String, locales: Set<String>): List<String> {
        val first = listOf("$language-${DEFAULT_REGIONS[language]}", language).filter { it in locales }
        return first + locales.filter { it.startsWith("$language-") && it !in first }.sorted()
    }

    /** A short description as the index carries it: one line of at most [MAX_SUMMARY] characters. */
    internal fun summaryText(text: String): String =
        text.removePrefix("\uFEFF").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(MAX_SUMMARY)

    /** Files by the number their name begins with, so that 2.png comes before 10.png, then by name. */
    private val NATURAL = compareBy<String>(
        { Regex("^\\d+").find(it.substringAfterLast('/')) == null },
        { Regex("^\\d+").find(it.substringAfterLast('/'))?.value?.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO },
        { it.substringAfterLast('/') }
    )
}
