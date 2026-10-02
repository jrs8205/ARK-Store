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
        /**
         * The prerelease at the top of the release list, if there is one. Whether it is offered
         * is decided by [CatalogRules.offered].
         */
        val beta: StoreApp?,
        /** Found through the store topic rather than only through a source on this device. */
        val discovered: Boolean
    ) {
        val hasApp: Boolean get() = app != null || beta != null

        /** Whether the store index lists the app as found by searching GitHub. */
        val auto: Boolean get() = (app ?: beta)?.auto == true

        fun mapApps(transform: (StoreApp) -> StoreApp) =
            Entry(stamp, fetchedAt, app?.let(transform), beta?.let(transform), discovered)

        /** This entry marked the way the store index lists its repository. */
        fun listedAs(auto: Boolean) = Entry(
            stamp,
            fetchedAt,
            app?.copy(auto = auto),
            beta?.copy(auto = auto),
            discovered = true
        )
    }

    val sources = SourceStore(context)

    private val deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList()

    private val preferences = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)

    private val _includeBeta = MutableStateFlow(preferences.getBoolean(PREF_BETA, false))
    /** Whether prereleases are offered as the version to install. */
    val includeBeta: StateFlow<Boolean> = _includeBeta.asStateFlow()

    private val _includeAuto = MutableStateFlow(preferences.getBoolean(PREF_AUTO, false))
    /** Whether apps found by searching GitHub, which nobody published here, are shown. */
    val includeAuto: StateFlow<Boolean> = _includeAuto.asStateFlow()

    private val indexCache = File(context.filesDir, "index.json")
    private val autoCache = File(context.filesDir, "auto.json")

    private val file = File(context.filesDir, "catalog.json")

    /** Held for a whole refresh, network requests included. */
    private val mutex = Mutex()

    /**
     * Guards the moment [entries] and the settings turn into the published catalogue. It is
     * held only for that moment and never across I/O, so a setting can be changed while a
     * refresh is running and neither overwrites what the other published.
     */
    private val publishLock = Any()
    private var entries: Map<String, Entry> = emptyMap()
    private var storeDownloads: Long? = null
    private var storeDownloadsAt = 0L

    private val _catalog = MutableStateFlow(Catalog.EMPTY)
    val catalog: StateFlow<Catalog> = _catalog.asStateFlow()

    /** What the app of the repository [fullName] is called in the store. */
    fun titleOf(fullName: String): String =
        _catalog.value.apps.firstOrNull { it.fullName == fullName }?.title
            ?: fullName.substringAfter('/')

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
                    val listed = listRepositories(source)
                    // A repository that was renamed or moved still answers at its old
                    // address, under its new name. The source follows it there, so that it
                    // keeps matching its own app.
                    val current = listed.singleOrNull()?.getString("full_name")
                        ?.takeIf { SourceStore.isRepository(source) }
                    if (current != null && !current.equals(source, ignoreCase = true)) {
                        Log.i(TAG, "Source $source is now $current")
                        sources.rename(source, current)
                    }
                    for (repo in listed) {
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
                    updated += entries.filterKeys { CatalogRules.belongsTo(it, source) }
                }
            }

            // The index speaks for every repository that is not asked about directly. The
            // exception is one kept from a source that could not be reached, when it was read
            // from GitHub after the index was built: falling back to the index would offer an
            // older release than the one already known.
            indexed?.forEach { (fullName, entry) ->
                if (fullName in repos) return@forEach
                val kept = updated[fullName]
                val newer = kept != null && kept.stamp > entry.stamp
                updated[fullName] = if (newer) kept.listedAs(entry.auto) else entry
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
                        // How the app was found is the index's to say. Without an index every
                        // repository here comes from its topic or from a source, so none
                        // counts as found by searching, whatever was known before.
                        val auto = indexed?.get(fullName)?.auto == true
                        val maxAge = if (foreground && old?.hasApp == true) {
                            foregroundAge
                        } else {
                            maxOf(MAX_AGE_BACKGROUND_MS, foregroundAge)
                        }
                        if (old != null && old.stamp == stamp && now - old.fetchedAt < maxAge) {
                            // These come with the repository list, so they are always fresh.
                            val fresh = old.mapApps {
                                it.copy(
                                    stars = stars,
                                    category = category,
                                    license = license,
                                    auto = auto
                                )
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
                                val listed = indexed?.get(fullName)
                                fetchApp(
                                    repo,
                                    listOfNotNull(old?.app, old?.beta, listed?.app, listed?.beta),
                                    auto
                                )
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

            refreshStoreDownloads(updated, now)
            val error = failure.get()
            val result = publish(updated, if (error == null) now else _catalog.value.checkedAt)
            save(result.checkedAt)
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
        val kept = entries.filter { (name, entry) ->
            entry.discovered || remaining.any { CatalogRules.belongsTo(name, it) }
        }
        val checkedAt = publish(kept, _catalog.value.checkedAt).checkedAt
        withContext(Dispatchers.IO) { save(checkedAt) }
    }

    /**
     * Updates the download count of the store's own releases. When the store is itself in the
     * catalogue the number is already there; otherwise it costs one request every few hours.
     * It is a nicety, so a failure only means the previous number stays.
     */
    private fun refreshStoreDownloads(entries: Map<String, Entry>, now: Long) {
        val listed = entries.entries
            .firstOrNull { it.key.equals(BuildConfig.STORE_REPO, ignoreCase = true) }?.value
            ?.let { it.app ?: it.beta }
        if (listed != null) {
            storeDownloads = listed.allDownloads
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
        val root = JSONObject(cachedText(BuildConfig.INDEX_URL, indexCache, PREF_INDEX_ETAG))
        if (now - root.getLong("generatedAt") > INDEX_MAX_AGE_MS) {
            Log.w(TAG, "Store index is stale")
            null
        } else {
            // "apps" have a full release and possibly a newer prerelease under "beta";
            // "betaApps" have nothing but a prerelease.
            val stable = objects(root, "apps").associate { app ->
                app.getString("fullName") to Entry(
                    stamp = app.optString("pushedAt"),
                    fetchedAt = now,
                    app = indexedApp(app, app),
                    beta = app.optJSONObject("beta")?.let { indexedApp(app, it, prerelease = true) },
                    discovered = true
                )
            }
            val betaOnly = objects(root, "betaApps").associate { app ->
                app.getString("fullName") to Entry(
                    stamp = app.optString("pushedAt"),
                    fetchedAt = now,
                    app = null,
                    beta = indexedApp(app, app, prerelease = true),
                    discovered = true
                )
            }
            autoEntries(root, now) + betaOnly + stable
        }
    } catch (e: IOException) {
        Log.w(TAG, "Store index unavailable", e)
        null
    } catch (e: JSONException) {
        Log.w(TAG, "Store index unreadable", e)
        null
    }

    private fun objects(root: JSONObject, key: String): List<JSONObject> {
        val array = root.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    /**
     * The apps found by searching GitHub, which their developers did not publish. They are
     * many and wanted by few, so they have a file of their own that is downloaded only while
     * they are shown. An [index] built before that file existed carries the list itself.
     *
     * The list is an extra: when it cannot be had, the last copy is used, and failing that
     * the catalogue simply goes without.
     */
    private fun autoEntries(index: JSONObject, now: Long): Map<String, Entry> {
        if (!_includeAuto.value) return emptyMap()
        val text = try {
            cachedText(BuildConfig.AUTO_INDEX_URL, autoCache, PREF_AUTO_ETAG)
        } catch (e: IOException) {
            Log.w(TAG, "List of automatically found apps unavailable", e)
            try {
                autoCache.takeIf { it.exists() }?.readText()
            } catch (_: IOException) {
                null
            }
        }
        return try {
            val root = text?.let(::JSONObject) ?: index
            objects(root, "autoApps").associate { app ->
                app.getString("fullName") to Entry(
                    stamp = app.optString("pushedAt"),
                    fetchedAt = now,
                    app = indexedApp(app, app, auto = true),
                    beta = null,
                    discovered = true
                )
            }
        } catch (e: JSONException) {
            Log.w(TAG, "List of automatically found apps unreadable", e)
            emptyMap()
        }
    }

    /**
     * The text at [url], one of the files the index workflow rebuilds about once an hour. The
     * copy kept in [cache] from the last download is used for as long as the server says
     * nothing has changed; [etagKey] is where that download's ETag is kept.
     */
    private fun cachedText(url: String, cache: File, etagKey: String): String {
        val etag = preferences.getString(etagKey, null).takeIf { cache.exists() }
        val (text, newEtag) = Http.getTextIfChanged(url, etag)
        if (text == null) return cache.readText()
        try {
            cache.writeText(text)
            preferences.edit { putString(etagKey, newEtag) }
        } catch (e: IOException) {
            // The copy only saves a download next time.
            Log.w(TAG, "Could not keep a copy of ${cache.name}", e)
            preferences.edit { remove(etagKey) }
        }
        return text
    }

    /**
     * Builds an app from its index entry [json] and one of the releases described there, or
     * returns null when none of that release's APKs suits this device.
     */
    private fun indexedApp(
        json: JSONObject,
        release: JSONObject,
        prerelease: Boolean = false,
        auto: Boolean = false
    ): StoreApp? {
        val apks = release.getJSONArray("apks").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }
        }
        val apk = ApkPicker.pick(apks.map { it.getString("name") }, deviceAbis)
            ?.let { apks[it] }
            ?: return null
        val topics = json.optJSONArray("topics")
            ?.let { array -> List(array.length()) { array.getString(it) } }
            .orEmpty()
        val allDownloads = json.optLong("downloads")
        return StoreApp(
            fullName = json.getString("fullName"),
            description = json.optStringOrEmpty("description"),
            stars = json.optInt("stars"),
            category = Categories.of(topics),
            license = json.optStringOrEmpty("license"),
            // An entry written before the index told the two apart counts every download for
            // both.
            downloads = if (json.has("betaDownloads")) {
                CatalogRules.downloads(allDownloads, json.optLong("betaDownloads"), prerelease)
            } else {
                allDownloads
            },
            repoUrl = json.getString("repoUrl"),
            prerelease = prerelease,
            auto = auto,
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
            versionName = if (apk.isNull("versionName")) null else apk.getString("versionName"),
            label = if (apk.isNull("label")) null else apk.getString("label"),
            allDownloads = allDownloads
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

    /**
     * Looks up a repository's releases and returns its newest full release and, when the
     * release at the top of the list is a prerelease, that one as well. Either is null when it
     * does not exist or has no APK for this device. [previous] is what was known before, so
     * that APKs already examined are not fetched again; [auto] is how the index found the app.
     */
    private fun fetchApp(
        repo: JSONObject,
        previous: List<StoreApp>,
        auto: Boolean
    ): Pair<StoreApp?, StoreApp?> {
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
        val betaDownloads = releases.filter { it.optBoolean("prerelease") }
            .sumOf { r -> apkAssets(r).sumOf { it.optLong("download_count") } }

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
                downloads = CatalogRules.downloads(downloads, betaDownloads, prerelease),
                repoUrl = repo.getString("html_url"),
                prerelease = prerelease,
                auto = auto,
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
                versionName = info?.versionName,
                // Only the index reads an app's name. A release newer than the index is
                // taken to be called what the one before it was.
                label = known?.label ?: previous.firstNotNullOfOrNull { it.label },
                allDownloads = downloads
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
        // A prerelease at the top of the list is usually newer than the full release, but the
        // list is ordered by commit date; CatalogRules.offered compares the versions.
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
        val checkedAt = publish(entries + (fullName to fixed), _catalog.value.checkedAt).checkedAt
        withContext(Dispatchers.IO) { save(checkedAt) }
    }

    /**
     * Shows or hides automatically found apps and republishes the catalogue accordingly.
     * Their list is downloaded only while they are shown, so after turning them on they
     * appear with the next refresh.
     */
    fun setIncludeAuto(include: Boolean) {
        preferences.edit { putBoolean(PREF_AUTO, include) }
        synchronized(publishLock) {
            _includeAuto.value = include
            _catalog.value = _catalog.value.copy(apps = sorted(entries.values))
        }
    }

    /** Turns beta versions on or off and republishes the catalogue accordingly. */
    fun setIncludeBeta(include: Boolean) {
        preferences.edit { putBoolean(PREF_BETA, include) }
        synchronized(publishLock) {
            _includeBeta.value = include
            _catalog.value = _catalog.value.copy(apps = sorted(entries.values))
        }
    }

    /** Makes [newEntries] the catalogue and publishes it. */
    private fun publish(newEntries: Map<String, Entry>, checkedAt: Long): Catalog =
        synchronized(publishLock) {
            entries = newEntries
            Catalog(
                apps = sorted(newEntries.values),
                checkedAt = checkedAt,
                storeDownloads = storeDownloads,
                betaVersions = betaVersions(newEntries.values)
            ).also { _catalog.value = it }
        }

    /** The apps to show, each in the version to offer. */
    private fun sorted(entries: Collection<Entry>): List<StoreApp> {
        val includeBeta = _includeBeta.value
        val includeAuto = _includeAuto.value
        val own = sources.list()
        return entries.mapNotNull { CatalogRules.offered(it.app, it.beta, includeBeta) }
            .mapNotNull { CatalogRules.shown(it, own, includeAuto) }
            .sortedBy { it.repo.lowercase() }
    }

    private fun betaVersions(entries: Collection<Entry>): Map<String, Long> =
        entries.mapNotNull { entry ->
            val stable = entry.app ?: return@mapNotNull null
            val beta = entry.beta?.takeIf { CatalogRules.upgrades(it, stable) }
            beta?.let { stable.fullName to it.versionCode }
        }.toMap()

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
            publish(entries, root.optLong("checkedAt"))
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
        private const val PREF_AUTO = "include_auto"
        private const val PREF_INDEX_ETAG = "index_etag"
        private const val PREF_AUTO_ETAG = "auto_etag"
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
