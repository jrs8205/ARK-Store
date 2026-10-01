package org.jarsi.arkstore.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jarsi.arkstore.BuildConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Builds the catalogue from the configured GitHub sources: every public repository whose
 * latest release has an APK attached becomes an app.
 *
 * Anonymous GitHub API calls are limited to 60 an hour, so a repository's releases are looked
 * up again only when the repository has been pushed to since, or the stored answer has grown
 * old.
 */
class CatalogRepository private constructor(context: Context) {

    private class Entry(val stamp: String, val fetchedAt: Long, val app: StoreApp?)

    val sources = SourceStore(context)

    private val deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList()

    private val file = File(context.filesDir, "catalog.json")
    private val mutex = Mutex()
    private var entries: Map<String, Entry> = emptyMap()
    private var storeDownloads: Long? = null
    private var storeDownloadsAt = 0L

    private val _catalog = MutableStateFlow(Catalog.EMPTY)
    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()

    init {
        load()
    }

    /**
     * Refreshes the catalogue. Stars are always current because they come with the repository
     * list. With [foreground] the releases of listed apps, and with them the download counts,
     * are trusted only for a short time; repositories that have no APK are still rechecked
     * rarely, since asking about them every time would use up the request quota.
     *
     * A source or repository that cannot be reached keeps its previous state. The rest of the
     * catalogue is still updated and published, but the call then throws and the time of the
     * last successful check stays where it was, so callers can tell the user and try again.
     */
    @Throws(IOException::class)
    suspend fun refresh(foreground: Boolean): Catalog = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val limiter = Semaphore(PARALLEL_REQUESTS)
            val failure = AtomicReference<IOException?>(null)
            // A quota error explains every later failure, so it is the one worth reporting.
            fun record(e: IOException) {
                failure.updateAndGet { if (it is RateLimitedException) it else e }
            }

            val updated = HashMap<String, Entry>()
            for (source in sources.list()) {
                val repos = try {
                    listRepositories(source)
                } catch (e: IOException) {
                    Log.w(TAG, "Source $source unavailable", e)
                    record(e)
                    updated += entries.filterKeys { belongsTo(it, source) }
                    continue
                }

                updated += coroutineScope {
                    repos.map { repo ->
                        async {
                            val fullName = repo.getString("full_name")
                            val stamp = repo.optString("pushed_at")
                            val stars = repo.optInt("stargazers_count")
                            val category = Categories.of(topics(repo))
                            val license = licenseOf(repo).orEmpty()
                            val old = entries[fullName]
                            val maxAge = if (foreground && old?.app != null) {
                                MAX_AGE_FOREGROUND_MS
                            } else {
                                MAX_AGE_BACKGROUND_MS
                            }
                            if (old != null && old.stamp == stamp && now - old.fetchedAt < maxAge) {
                                // These come with the repository list, so they are always fresh.
                                val app = old.app?.copy(
                                    stars = stars,
                                    category = category,
                                    license = license
                                )
                                return@async fullName to Entry(old.stamp, old.fetchedAt, app)
                            }
                            try {
                                val app = limiter.withPermit { fetchApp(repo, old?.app) }
                                fullName to Entry(stamp, now, app)
                            } catch (e: IOException) {
                                Log.w(TAG, "Release lookup failed for $fullName", e)
                                record(e)
                                old?.let { fullName to it }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            }

            entries = updated
            refreshStoreDownloads(now)
            val error = failure.get()
            val result = Catalog(
                apps = sorted(updated.values),
                checkedAt = if (error == null) now else _catalog.value.checkedAt,
                storeDownloads = storeDownloads
            )
            save(result.checkedAt)
            _catalog.value = result
            if (error != null) throw error
            result
        }
    }

    /**
     * Checks that [source] exists on GitHub; throws [HttpStatusException] 404 when it does not.
     * Returns false for a repository that exists but is not open source and so cannot be listed.
     */
    @Throws(IOException::class)
    suspend fun verifySource(source: String): Boolean = withContext(Dispatchers.IO) {
        if (SourceStore.isRepository(source)) {
            isListable(JSONObject(Http.getApi("$API/repos/$source")))
        } else {
            Http.getApi("$API/users/$source")
            true
        }
    }

    /** Drops a source together with the apps that came from it. */
    suspend fun removeSource(source: String) = mutex.withLock {
        sources.remove(source)
        val remaining = sources.list()
        entries = entries.filterKeys { name -> remaining.any { belongsTo(name, it) } }
        val checkedAt = _catalog.value.checkedAt
        _catalog.value = Catalog(sorted(entries.values), checkedAt, storeDownloads)
        withContext(Dispatchers.IO) { save(checkedAt) }
    }

    /**
     * Updates the download count of the store's own releases. When the store is itself in the
     * catalogue the number is already there; otherwise it costs one request every few hours.
     * It is a nicety, so a failure only means the previous number stays.
     */
    private fun refreshStoreDownloads(now: Long) {
        val listed = entries.entries
            .firstOrNull { it.key.equals(BuildConfig.STORE_REPO, ignoreCase = true) }?.value?.app
        if (listed != null) {
            storeDownloads = listed.downloads
            storeDownloadsAt = now
            return
        }
        if (now - storeDownloadsAt < MAX_AGE_BACKGROUND_MS) return
        try {
            val releases = JSONArray(
                Http.getApi("$API/repos/${BuildConfig.STORE_REPO}/releases?per_page=$PAGE_SIZE")
            )
            var total = 0L
            for (i in 0 until releases.length()) {
                val assets = releases.getJSONObject(i).optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                        total += asset.optLong("download_count")
                    }
                }
            }
            storeDownloads = total
            storeDownloadsAt = now
        } catch (e: IOException) {
            Log.w(TAG, "Store download count unavailable", e)
            // Do not ask again right away when the repository is simply not public.
            if (e is HttpStatusException && e.code == 404) storeDownloadsAt = now
        } catch (e: JSONException) {
            Log.w(TAG, "Store download count unreadable", e)
        }
    }

    private fun listRepositories(source: String): List<JSONObject> {
        if (SourceStore.isRepository(source)) {
            return listOf(JSONObject(Http.getApi("$API/repos/$source"))).filter(::isListable)
        }
        val repos = ArrayList<JSONObject>()
        for (page in 1..MAX_REPO_PAGES) {
            val batch = JSONArray(
                Http.getApi("$API/users/$source/repos?per_page=$PAGE_SIZE&type=owner&page=$page")
            )
            for (i in 0 until batch.length()) repos += batch.getJSONObject(i)
            if (batch.length() < PAGE_SIZE) break
        }
        return repos.filter { !it.optBoolean("fork") && !it.optBoolean("archived") }
            .filter(::isListable)
    }

    /**
     * Only public repositories under an open-source licence that GitHub recognises are listed.
     * This keeps closed or unlicensed software, whose origin nobody can check, out of the store.
     */
    private fun isListable(repo: JSONObject): Boolean =
        !repo.optBoolean("private") && !repo.optBoolean("disabled") && licenseOf(repo) != null

    private fun licenseOf(repo: JSONObject): String? =
        repo.optJSONObject("license")?.optString("spdx_id")
            ?.takeIf { it.isNotBlank() && it != "NOASSERTION" }

    private fun topics(repo: JSONObject): List<String> {
        val array = repo.optJSONArray("topics") ?: return emptyList()
        return List(array.length()) { array.getString(it) }
    }

    private fun belongsTo(fullName: String, source: String): Boolean =
        if (SourceStore.isRepository(source)) {
            fullName.equals(source, ignoreCase = true)
        } else {
            fullName.substringBefore('/').equals(source, ignoreCase = true)
        }

    private fun fetchApp(repo: JSONObject, previous: StoreApp?): StoreApp? {
        val fullName = repo.getString("full_name")
        val page = JSONArray(Http.getApi("$API/repos/$fullName/releases?per_page=$PAGE_SIZE"))
            .let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
        val releases = page.filter { !it.optBoolean("draft") }

        fun apkAssets(release: JSONObject): List<JSONObject> {
            val assets = release.optJSONArray("assets") ?: return emptyList()
            return (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
        }

        // Releases come newest first; the first full release is what GitHub calls "latest".
        // When a full page holds nothing but prereleases, the stable one lies further back.
        val release = releases.firstOrNull { !it.optBoolean("prerelease") }
            ?: if (page.size < PAGE_SIZE) {
                return null
            } else {
                try {
                    JSONObject(Http.getApi("$API/repos/$fullName/releases/latest"))
                } catch (e: HttpStatusException) {
                    if (e.code == 404) return null else throw e
                }
            }
        val candidates = apkAssets(release)
        val apk = ApkPicker.pick(candidates.map { it.getString("name") }, deviceAbis)
            ?.let { candidates[it] }
            ?: return null
        val downloads = releases.sumOf { r -> apkAssets(r).sumOf { it.optLong("download_count") } }

        val assetId = apk.getLong("id")
        val url = apk.getString("browser_download_url")
        val size = apk.getLong("size")
        // The asset id changes whenever a file is replaced, so a known id means a known APK.
        val info = if (previous != null && previous.assetId == assetId && previous.packageName != null) {
            ApkInfo(previous.packageName, previous.versionCode, previous.versionName)
        } else {
            try {
                ApkManifestReader.read(size) { start, length -> Http.readRange(url, start, length) }
            } catch (e: IOException) {
                Log.w(TAG, "Could not read manifest of ${apk.getString("name")}", e)
                null
            }
        }

        return StoreApp(
            fullName = fullName,
            description = repo.optStringOrEmpty("description"),
            stars = repo.optInt("stargazers_count"),
            category = Categories.of(topics(repo)),
            license = licenseOf(repo).orEmpty(),
            downloads = downloads,
            repoUrl = repo.getString("html_url"),
            tag = release.getString("tag_name"),
            releaseName = release.optStringOrEmpty("name"),
            releaseNotes = release.optStringOrEmpty("body"),
            releaseUrl = release.optStringOrEmpty("html_url"),
            publishedAt = release.optStringOrEmpty("published_at"),
            apkName = apk.getString("name"),
            apkUrl = url,
            apkSize = size,
            assetId = assetId,
            packageName = info?.packageName,
            versionCode = info?.versionCode ?: 0,
            versionName = info?.versionName
        )
    }

    /**
     * Records what a downloaded APK turned out to be when its manifest was unreadable remotely.
     * [assetId] names the file that was downloaded: if the catalogue has moved on to a newer
     * release in the meantime, the information no longer applies and is dropped.
     */
    suspend fun rememberApkInfo(fullName: String, assetId: Long, info: ApkInfo) = mutex.withLock {
        val entry = entries[fullName] ?: return@withLock
        val app = entry.app ?: return@withLock
        if (app.assetId != assetId) return@withLock
        if (app.packageName == info.packageName && app.versionCode == info.versionCode) return@withLock
        val fixed = app.copy(
            packageName = info.packageName,
            versionCode = info.versionCode,
            versionName = info.versionName
        )
        entries = entries + (fullName to Entry(entry.stamp, entry.fetchedAt, fixed))
        val checkedAt = _catalog.value.checkedAt
        _catalog.value = Catalog(sorted(entries.values), checkedAt, storeDownloads)
        withContext(Dispatchers.IO) { save(checkedAt) }
    }

    private fun sorted(entries: Collection<Entry>): List<StoreApp> =
        entries.mapNotNull { it.app }.sortedBy { it.repo.lowercase() }

    private fun load() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val repos = root.getJSONObject("repos")
            entries = repos.keys().asSequence().associateWith { name ->
                val entry = repos.getJSONObject(name)
                Entry(
                    stamp = entry.getString("stamp"),
                    fetchedAt = entry.getLong("fetchedAt"),
                    app = entry.optJSONObject("app")?.let(StoreApp::fromJson)
                )
            }
            storeDownloads = if (root.isNull("storeDownloads")) null else root.getLong("storeDownloads")
            storeDownloadsAt = root.optLong("storeDownloadsAt")
            _catalog.value = Catalog(sorted(entries.values), root.optLong("checkedAt"), storeDownloads)
        } catch (e: Exception) {
            Log.w(TAG, "Stored catalogue unreadable, starting empty", e)
            entries = emptyMap()
        }
    }

    private fun save(checkedAt: Long) {
        val repos = JSONObject()
        entries.forEach { (name, entry) ->
            repos.put(
                name,
                JSONObject()
                    .put("stamp", entry.stamp)
                    .put("fetchedAt", entry.fetchedAt)
                    .put("app", entry.app?.toJson() ?: JSONObject.NULL)
            )
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(
            JSONObject()
                .put("checkedAt", checkedAt)
                .put("storeDownloads", storeDownloads ?: JSONObject.NULL)
                .put("storeDownloadsAt", storeDownloadsAt)
                .put("repos", repos)
                .toString()
        )
        temp.renameTo(file)
    }

    private fun JSONObject.optStringOrEmpty(key: String): String =
        if (isNull(key)) "" else optString(key)

    companion object {
        private const val TAG = "CatalogRepository"
        private const val API = "https://api.github.com"
        private const val PARALLEL_REQUESTS = 4
        private const val PAGE_SIZE = 100
        private const val MAX_REPO_PAGES = 10
        private const val MAX_AGE_FOREGROUND_MS = 20 * 60 * 1000L
        private const val MAX_AGE_BACKGROUND_MS = 6 * 60 * 60 * 1000L

        @Volatile
        private var instance: CatalogRepository? = null

        fun get(context: Context): CatalogRepository = instance ?: synchronized(this) {
            instance ?: CatalogRepository(context.applicationContext).also { instance = it }
        }
    }
}
