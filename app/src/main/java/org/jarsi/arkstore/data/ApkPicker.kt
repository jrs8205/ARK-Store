package org.jarsi.arkstore.data

/**
 * Chooses which of a release's APK files suits this device. Releases built per CPU architecture
 * name the architecture in the file name; a file that names none is taken to run everywhere.
 */
object ApkPicker {

    // Longer markers first, so "x86_64" is not mistaken for "x86" nor "arm64" for "arm".
    private val MARKERS = listOf(
        "arm64-v8a" to "arm64-v8a",
        "arm64" to "arm64-v8a",
        "aarch64" to "arm64-v8a",
        "armeabi-v7a" to "armeabi-v7a",
        "armeabi" to "armeabi-v7a",
        "armv7" to "armeabi-v7a",
        "arm32" to "armeabi-v7a",
        "x86_64" to "x86_64",
        "x86-64" to "x86_64",
        "x64" to "x86_64",
        "amd64" to "x86_64",
        "x86" to "x86",
        "i686" to "x86"
    )

    /** The architectures named in [fileName]; empty when it names none or says "universal". */
    internal fun architectures(fileName: String): Set<String> {
        var rest = fileName.lowercase()
        if ("universal" in rest) return emptySet()
        val found = HashSet<String>()
        for ((marker, abi) in MARKERS) {
            if (marker in rest) {
                found += abi
                rest = rest.replace(marker, " ")
            }
        }
        return found
    }

    /**
     * Returns the index of the best file in [fileNames] for a device supporting [deviceAbis]
     * (most preferred first), or null when every file is built for another architecture.
     * The device's preferred architecture wins, then a universal file; debug builds lose ties.
     */
    fun pick(fileNames: List<String>, deviceAbis: List<String>): Int? =
        fileNames.withIndex().mapNotNull { (index, name) ->
            val architectures = architectures(name)
            val rank = when {
                architectures.isEmpty() -> deviceAbis.size
                else -> deviceAbis.indexOfFirst { it in architectures }.takeIf { it >= 0 }
            } ?: return@mapNotNull null
            val debugPenalty = if (name.contains("debug", ignoreCase = true)) 1 else 0
            Triple(index, debugPenalty, rank)
        }.minWithOrNull(compareBy<Triple<Int, Int, Int>>({ it.second }, { it.third }, { it.first }))
            ?.first

    /**
     * [pick] among the files that run on the device's Android, [runs] telling which do, or
     * failing that among them all: a file the device cannot run is still named, as the
     * version an installed app would need a newer Android for. The index is into [fileNames].
     */
    fun pickRunnable(fileNames: List<String>, runs: List<Boolean>, deviceAbis: List<String>): Int? {
        val runnable = fileNames.indices.filter { runs[it] }
        return pick(runnable.map { fileNames[it] }, deviceAbis)?.let { runnable[it] }
            ?: pick(fileNames, deviceAbis)
    }
}
