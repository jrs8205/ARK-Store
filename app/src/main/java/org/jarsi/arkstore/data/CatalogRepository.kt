package org.jarsi.arkstore.data

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
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
 * Builds the catalogue from GitHub. Developers publish an app by adding the store topic to its
 * repository, which makes it show up for everyone: those apps are read from the store index, a
 * single file rebuilt on a schedule. The sources configured on this device add further accounts
 * and repositories, which are looked up through the API. Every public, open-source repository
 * found either way whose latest release has an APK attached becomes an app.
 *
 * Anonymous GitHub API calls are limited to 60 an hour, so a repository's releases are looked
 * up again only when the repository has been pushed to since, or the stored answer has grown
 * old.
 */
class CatalogRepository private constructor(context: Context) {

    private class Entry(
        val stamp: String,
        val fetchedAt: Long,
        /** The newest full release, if there is one for this device. */
        val app: StoreApp?,
        /** A prerelease newer than [app], offered only when beta versions are wanted. */
        val beta: StoreApp?,
        /** Found through the store topic rather than only through a source on this device. */
        val discovered: Boolean
    ) {
        val hasApp: Boolean get() = app != null || beta != null

        fun mapApps(transform: (StoreApp) -> StoreApp) =
            Entry(stamp, fetchedAt, app?.let(transform), beta?.let(transform), discovered)
    }

    val sources = SourceStore(context)

    private val deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList()

    private val preferences = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)

    private val _includeBeta = MutableStateFlow(preferences.getBoolean(PREF_BETA, false))
    /** Whether prereleases are offered as the version to install. */
    val includeBeta: StateFlow<Boolean> = _includeBeta.asStateFlow()

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
            // Every repository to look at, keyed by full name so that one found both through
            // its topic and through a source is handled once.
            val repos = LinkedHashMap<String, JSONObject>()
            val discovered = HashSet<String>()

            // Published apps normally come ready-made from the store index. Only when that is
            // missing or stale are they looked up one by one through the API.
            val indexed = loadIndex(now)
            if (indexed != null) {
                discovered += indexed.keys
            } else {
                try {
                    for (repo in discoverRepositories()) {
                        val fullName = repo.getString("full_name")
                        repos[fullName] = repo
                        discovered += fullName
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Discovery unavailable", e)
                    record(e)
                    updated += entries.filterValues { it.discovered }
                }
            }
            for (source in sources.list()) {
                try {
                    for (repo in listRepositories(source)) {
                        val fullName = repo.getString("full_name")
                        // The index is rebuilt about hourly. A repository pushed to since then
                        // is asked about directly, so a release just made shows up at once.
                        val known = indexed?.get(fullName)
                        if (known != null && known.stamp >= repo.optString("pushed_at")) continue
                        repos.putIfAbsent(fullName, repo)
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Source $source unavailable", e)
                    record(e)
                    updated += entries.filterKeys { belongsTo(it, source) }
                }
            }

            indexed?.forEach { (fullName, entry) ->
                if (fullName !in repos) updated[fullName] = entry
            }

            // The more apps there are, the longer each stored answer has to last, or the
            // hourly request quota would not cover them all.
            val known = repos.keys.count { entries[it]?.hasApp == true }
            val foregroundAge = maxOf(MAX_AGE_FOREGROUND_MS, known * AGE_PER_APP_MS)

            updated += coroutineScope {
                repos.map { (fullName, repo) ->
                    async {
                        val isDiscovered = fullName in discovered
                        val stamp = repo.optString("pushed_at")
                        val stars = repo.optInt("stargazers_count")
                        val category = Categories.of(topics(repo))
                        val license = licenseOf(repo).orEmpty()
                        val old = entries[fullName]
                        val maxAge = if (foreground && old?.hasApp == true) {
                            foregroundAge
                        } else {
                            maxOf(MAX_AGE_BACKGROUND_MS, foregroundAge)
                        }
                        if (old != null && old.stamp == stamp && now - old.fetchedAt < maxAge) {
                            // These come with the repository list, so they are always fresh.
                            val fresh = old.mapApps {
                                it.copy(stars = stars, category = category, license = license)
                            }
                            return@async fullName to Entry(
                                fresh.stamp,
                                fresh.fetchedAt,
                                fresh.app,
                                fresh.beta,
                                isDiscovered
                            )
                        }
                        try {
                            val (app, beta) = limiter.withPermit {
                                fetchApp(repo, listOfNotNull(old?.app, old?.beta))
                            }
                            fullName to Entry(stamp, now, app, beta, isDiscovered)
                        } catch (e: IOException) {
                            Log.w(TAG, "Release lookup failed for $fullName", e)
                            record(e)
                            old?.let { fullName to it }
                        }
                    }
                }.awaitAll().filterNotNull()
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
        entries = entries.filter { (name, entry) ->
            entry.discovered || remaining.any { belongsTo(name, it) }
        }
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
            .firstOrNull { it.key.equals(BuildConfig.STORE_REPO, ignoreCase = true) }?.value
            ?.let { it.app ?: it.beta }
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

    /**
     * Reads the store index: every published app, described in one file that is rebuilt on a
     * schedule in the store's own repository. Returns null when the file cannot be had or has
     * not been rebuilt for so long that it cannot be trusted.
     */
    private fun loadIndex(now: Long): Map<String, Entry>? = try {
        val root = JSONObject(Http.getText(BuildConfig.INDEX_URL))
        if (now - root.getLong("generatedAt") > INDEX_MAX_AGE_MS) {
            Log.w(TAG, "Store index is stale")
            null
        } else {
            fun objects(key: String): List<JSONObject> {
                val array = root.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).map { array.getJSONObject(it) }
            }
            // "apps" have a full release and possibly a newer prerelease under "beta";
            // "betaApps" have nothing but a prerelease.
            val stable = objects("apps").associate { app ->
                app.getString("fullName") to Entry(
                    stamp = app.optString("pushedAt"),
                    fetchedAt = now,
                    app = indexedApp(app, app, prerelease = false),
                    beta = app.optJSONObject("beta")?.let { indexedApp(app, it, prerelease = true) },
                    discovered = true
                )
            }
            val betaOnly = objects("betaApps").associate { app ->
                app.getString("fullName") to Entry(
                    stamp = app.optString("pushedAt"),
                    fetchedAt = now,
                    app = null,
                    beta = indexedApp(app, app, prerelease = true),
                    discovered = true
                )
            }
            betaOnly + stable
        }
    } catch (e: IOException) {
        Log.w(TAG, "Store index unavailable", e)
        null
    } catch (e: JSONException) {
        Log.w(TAG, "Store index unreadable", e)
        null
    }

    /**
     * Builds an app from its index entry [json] and one of the releases described there, or
     * returns null when none of that release's APKs suits this device.
     */
    private fun indexedApp(json: JSONObject, release: JSONObject, prerelease: Boolean): StoreApp? {
        val apks = release.getJSONArray("apks").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }
        }
        val apk = ApkPicker.pick(apks.map { it.getString("name") }, deviceAbis)
            ?.let { apks[it] }
            ?: return null
        val topics = json.optJSONArray("topics")
            ?.let { array -> List(array.length()) { array.getString(it) } }
            .orEmpty()
        return StoreApp(
            fullName = json.getString("fullName"),
            description = json.optStringOrEmpty("description"),
            stars = json.optInt("stars"),
            category = Categories.of(topics),
            license = json.optStringOrEmpty("license"),
            downloads = json.optLong("downloads"),
            repoUrl = json.getString("repoUrl"),
            prerelease = prerelease,
            tag = release.getString("tag"),
            releaseName = release.optStringOrEmpty("releaseName"),
            releaseNotes = release.optStringOrEmpty("releaseNotes"),
            releaseUrl = release.optStringOrEmpty("releaseUrl"),
            publishedAt = release.optStringOrEmpty("publishedAt"),
            apkName = apk.getString("name"),
            apkUrl = apk.getString("url"),
            apkSize = apk.getLong("size"),
            assetId = apk.getLong("id"),
            packageName = apk.getString("packageName"),
            versionCode = apk.getLong("versionCode"),
            versionName = if (apk.isNull("versionName")) null else apk.getString("versionName")
        )
    }

    /** Repositories whose developers have tagged them with the store topic. */
    private fun discoverRepositories(): List<JSONObject> {
        val repos = ArrayList<JSONObject>()
        for (page in 1..MAX_DISCOVERY_PAGES) {
            val result = JSONObject(
                Http.getApi(
                    "$API/search/repositories?q=topic:${BuildConfig.STORE_TOPIC}+archived:false" +
                        "&sort=updated&per_page=$PAGE_SIZE&page=$page"
                )
            )
            val items = result.optJSONArray("items") ?: break
            for (i in 0 until items.length()) repos += items.getJSONObject(i)
            if (items.length() < PAGE_SIZE) break
        }
        return repos.filter { !it.optBoolean("fork") && !it.optBoolean("archived") }
            .filter(::isListable)
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

    /**
     * Looks up a repository's releases and returns its newest full release and, when the
     * newest release of all is a prerelease, that one as well. Either is null when it does not
     * exist or has no APK for this device. [previous] is what was known before, so that APKs
     * already examined are not fetched again.
     */
    private fun fetchApp(repo: JSONObject, previous: List<StoreApp>): Pair<StoreApp?, StoreApp?> {
        val fullName = repo.getString("full_name")
        val page = JSONArray(Http.getApi("$API/repos/$fullName/releases?per_page=$PAGE_SIZE"))
            .let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
        val releases = page.filter { !it.optBoolean("draft") }

        fun apkAssets(release: JSONObject): List<JSONObject> {
            val assets = release.optJSONArray("assets") ?: return emptyList()
            return (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
        }
        val downloads = releases.sumOf { r -> apkAssets(r).sumOf { it.optLong("download_count") } }

        fun build(release: JSONObject, prerelease: Boolean): StoreApp? {
            val candidates = apkAssets(release)
            val apk = ApkPicker.pick(candidates.map { it.getString("name") }, deviceAbis)
                ?.let { candidates[it] }
                ?: return null
            val assetId = apk.getLong("id")
            val url = apk.getString("browser_download_url")
            val size = apk.getLong("size")
            // The asset id changes whenever a file is replaced, so a known id means a known APK.
            val known = previous.firstOrNull { it.assetId == assetId && it.packageName != null }
            val info = if (known != null) {
                ApkInfo(known.packageName!!, known.versionCode, known.versionName)
            } else {
                try {
                    ApkManifestReader.read(size) { start, length ->
                        Http.readRange(url, start, length)
                    }
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
                prerelease = prerelease,
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

        // Releases come newest first; the first full release is what GitHub calls "latest".
        // When a full page holds nothing but prereleases, the stable one lies further back.
        val stable = releases.firstOrNull { !it.optBoolean("prerelease") }
            ?: if (page.size < PAGE_SIZE) {
                null
            } else {
                try {
                    JSONObject(Http.getApi("$API/repos/$fullName/releases/latest"))
                } catch (e: HttpStatusException) {
                    if (e.code == 404) null else throw e
                }
            }
        // A prerelease at the top of the list is newer than the full release.
        val newest = releases.firstOrNull()?.takeIf { it.optBoolean("prerelease") }
        return stable?.let { build(it, prerelease = false) } to
            newest?.let { build(it, prerelease = true) }
    }

    /**
     * Records what a downloaded APK turned out to be when its manifest was unreadable remotely.
     * [assetId] names the file that was downloaded: if the catalogue has moved on to a newer
     * release in the meantime, the information no longer applies and is dropped.
     */
    suspend fun rememberApkInfo(fullName: String, assetId: Long, info: ApkInfo) = mutex.withLock {
        val entry = entries[fullName] ?: return@withLock
        if (entry.app?.assetId != assetId && entry.beta?.assetId != assetId) return@withLock
        val fixed = entry.mapApps { app ->
            if (app.assetId != assetId) {
                app
            } else {
                app.copy(
                    packageName = info.packageName,
                    versionCode = info.versionCode,
                    versionName = info.versionName
                )
            }
        }
        if (fixed.app == entry.app && fixed.beta == entry.beta) return@withLock
        entries = entries + (fullName to fixed)
        val checkedAt = _catalog.value.checkedAt
        _catalog.value = Catalog(sorted(entries.values), checkedAt, storeDownloads)
        withContext(Dispatchers.IO) { save(checkedAt) }
    }

    /** Turns beta versions on or off and republishes the catalogue accordingly. */
    suspend fun setIncludeBeta(include: Boolean) = mutex.withLock {
        preferences.edit { putBoolean(PREF_BETA, include) }
        _includeBeta.value = include
        _catalog.value = _catalog.value.copy(apps = sorted(entries.values))
    }

    /** The apps to show: the prerelease where there is one and beta versions are wanted. */
    private fun sorted(entries: Collection<Entry>): List<StoreApp> {
        val includeBeta = _includeBeta.value
        return entries.mapNotNull { if (includeBeta) it.beta ?: it.app else it.app }
            .sortedBy { it.repo.lowercase() }
    }

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
                    app = entry.optJSONObject("app")?.let(StoreApp::fromJson),
                    beta = entry.optJSONObject("beta")?.let(StoreApp::fromJson),
                    discovered = entry.optBoolean("discovered")
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
                    .put("beta", entry.beta?.toJson() ?: JSONObject.NULL)
                    .put("discovered", entry.discovered)
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
        private const val PREF_BETA = "include_beta"
        private const val API = "https://api.github.com"
        private const val PARALLEL_REQUESTS = 4
        private const val PAGE_SIZE = 100
        private const val MAX_REPO_PAGES = 10
        private const val MAX_DISCOVERY_PAGES = 5
        private const val AGE_PER_APP_MS = 90 * 1000L
        private const val INDEX_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        private const val MAX_AGE_FOREGROUND_MS = 20 * 60 * 1000L
        private const val MAX_AGE_BACKGROUND_MS = 6 * 60 * 60 * 1000L

        @Volatile
        private var instance: CatalogRepository? = null

        fun get(context: Context): CatalogRepository = instance ?: synchronized(this) {
            instance ?: CatalogRepository(context.applicationContext).also { instance = it }
        }
    }
}
