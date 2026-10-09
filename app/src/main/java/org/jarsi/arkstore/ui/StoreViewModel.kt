package org.jarsi.arkstore.ui

import android.app.Application
import android.util.Log
import android.net.Uri
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.jarsi.arkstore.data.AppStatus
import org.jarsi.arkstore.data.Bookmarks
import org.jarsi.arkstore.data.Backup
import org.jarsi.arkstore.data.CatalogRepository
import org.jarsi.arkstore.data.CatalogRules
import org.jarsi.arkstore.data.GitHubToken
import org.jarsi.arkstore.data.HttpStatusException
import org.jarsi.arkstore.data.InstalledApps
import org.jarsi.arkstore.data.InstalledVersion
import org.jarsi.arkstore.data.RateLimitedException
import org.jarsi.arkstore.data.SourceStore
import org.jarsi.arkstore.data.StoreApp
import org.jarsi.arkstore.data.UpdatePolicy
import org.jarsi.arkstore.install.InstallManager
import org.jarsi.arkstore.work.AutoUpdate

data class AppRow(
    val app: StoreApp,
    val installed: InstalledVersion?,
    val status: AppStatus,
    /** The version code of the app's prerelease, when it has one that the store knows of. */
    val betaVersion: Long? = null,
    /** The other places that offer this app, by their source names. */
    val alsoFrom: List<String> = emptyList()
) {
    /**
     * A newer version than the one offered is installed. Android cannot put the older version
     * over it.
     */
    val newerInstalled: Boolean
        get() = installed != null && app.versionCode > 0 && installed.versionCode > app.versionCode

    /**
     * The newer installed version is a beta: the store installed it as one, or it lies between
     * the version offered and the prerelease the store knows of, as it does after beta
     * versions were turned off. Anything else came from somewhere else, such as another store
     * with its own version codes, and cannot be called a beta.
     */
    val betaInstalled: Boolean
        get() = newerInstalled &&
            (installed!!.beta || (betaVersion != null && installed.versionCode <= betaVersion))
}

enum class LoadError { NETWORK, RATE_LIMIT, TOKEN }

enum class SourceError { INVALID, DUPLICATE, NOT_FOUND, NOT_OPEN, NETWORK, RATE_LIMIT, TOKEN }

enum class TokenError { REJECTED, NETWORK, RATE_LIMIT }

enum class TransferError { NOT_A_BACKUP, FAILED }

/** What came of the last export or import, see [Backup]. */
data class TransferUiState(
    val busy: Boolean = false,
    val exported: Boolean = false,
    /** How many sources and how many bookmarks the import added. */
    val imported: Pair<Int, Int>? = null,
    val error: TransferError? = null
)

/** Whether a GitHub token is held, and what came of the last one offered. */
data class TokenUiState(
    val present: Boolean = false,
    /** The hourly limit of requests the token allows, when known. */
    val limit: Int? = null,
    val checking: Boolean = false,
    val error: TokenError? = null
)

data class SourcesUiState(
    val sources: List<String> = emptyList(),
    val adding: Boolean = false,
    val error: SourceError? = null
)

data class StoreUiState(
    val rows: List<AppRow> = emptyList(),
    val checkedAt: Long = 0,
    val storeDownloads: Long? = null,
    val refreshing: Boolean = false,
    val error: LoadError? = null
)

class StoreViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CatalogRepository.get(application)
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<LoadError?>(null)

    // Only touched on the main thread.
    private var refreshAgain = false
    private var lastResumeRefresh = 0L

    private val policy = MutableStateFlow(UpdatePolicy.read(application))
    /** The updates the user has asked not to be offered. */
    val updatePolicy: StateFlow<UpdatePolicy> = policy.asStateFlow()

    val state: StateFlow<StoreUiState> = combine(
        repository.catalog,
        InstallManager.installedChanged,
        refreshing,
        error,
        policy
    ) { catalog, _, isRefreshing, loadError, skips ->
        val packages = InstalledApps.snapshot(application)
        val android = CatalogRules.Android.THIS
        StoreUiState(
            rows = InstalledApps.merged(application, catalog.apps, packages).mapNotNull { row ->
                val app = row.app
                val runs = CatalogRules.runsOn(app, android)
                val installed = InstalledApps.find(application, row, packages)
                val status = InstalledApps.status(app, installed, runs, skips.skips(app))
                // An app this Android cannot run is listed only when it is installed, to say
                // that its newest version needs a newer Android.
                if (!CatalogRules.listed(status, runs)) return@mapNotNull null
                AppRow(app, installed, status, catalog.betaVersions[app.fullName], row.alsoFrom)
            },
            checkedAt = catalog.checkedAt,
            storeDownloads = catalog.storeDownloads,
            refreshing = isRefreshing,
            error = loadError
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, StoreUiState(refreshing = true))

    val includeBeta: StateFlow<Boolean> = repository.includeBeta
    val includeAuto: StateFlow<Boolean> = repository.includeAuto

    fun setIncludeAuto(include: Boolean) {
        repository.setIncludeAuto(include)
        // Their list is downloaded only once they are wanted.
        if (include) refresh()
    }

    fun setIncludeBeta(include: Boolean) = repository.setIncludeBeta(include)

    /** The other catalogues whose apps are shown, by their source names. */
    val catalogues: StateFlow<Set<String>> = repository.catalogues

    private val bookmarks = Bookmarks(application)

    /** The keys of the bookmarked apps, see [Bookmarks.keysOf]. */
    val bookmarked: StateFlow<Set<String>> = bookmarks.keys

    fun toggleBookmark(app: StoreApp) = bookmarks.toggle(app)

    fun setCatalogue(source: String, include: Boolean) {
        repository.setCatalogue(source, include)
        // A catalogue's list is downloaded only once it is wanted.
        if (include) refresh()
    }

    private val _sources = MutableStateFlow(SourcesUiState(sources = repository.sources.list()))
    val sources: StateFlow<SourcesUiState> = _sources

    init {
        lastResumeRefresh = System.currentTimeMillis()
        refresh()
    }

    /** Adds an account or repository typed by the user once GitHub confirms it exists. */
    fun addSource(input: String, onAdded: () -> Unit) {
        val source = SourceStore.parse(input)
        if (source == null) {
            _sources.update { it.copy(error = SourceError.INVALID) }
            return
        }
        if (repository.sources.list().any { it.equals(source, ignoreCase = true) }) {
            _sources.update { it.copy(error = SourceError.DUPLICATE) }
            return
        }
        _sources.update { it.copy(adding = true, error = null) }
        viewModelScope.launch {
            val failure = try {
                if (repository.verifySource(source)) null else SourceError.NOT_OPEN
            } catch (_: RateLimitedException) {
                SourceError.RATE_LIMIT
            } catch (e: HttpStatusException) {
                when (e.code) {
                    404 -> SourceError.NOT_FOUND
                    401 -> SourceError.TOKEN
                    else -> SourceError.NETWORK
                }
            } catch (_: IOException) {
                SourceError.NETWORK
            }
            if (failure == null) {
                repository.sources.add(source)
                onAdded()
                refresh()
            }
            _sources.update {
                SourcesUiState(sources = repository.sources.list(), error = failure)
            }
        }
    }

    fun removeSource(source: String) {
        viewModelScope.launch {
            repository.removeSource(source)
            _sources.update { SourcesUiState(sources = repository.sources.list()) }
        }
    }

    fun clearSourceError() = _sources.update { it.copy(error = null) }

    private val _token = MutableStateFlow(
        TokenUiState(present = GitHubToken.get() != null, limit = GitHubToken.limit(application))
    )
    val token: StateFlow<TokenUiState> = _token.asStateFlow()

    /** Keeps the token the user typed once GitHub says it is good, and tells what it allows. */
    fun saveToken(text: String) {
        val token = GitHubToken.tidy(text)
        if (token == null) {
            _token.update { it.copy(error = TokenError.REJECTED) }
            return
        }
        _token.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            val failure = try {
                GitHubToken.set(getApplication(), token, repository.checkToken(token))
                null
            } catch (_: RateLimitedException) {
                TokenError.RATE_LIMIT
            } catch (e: HttpStatusException) {
                if (e.code == 401) TokenError.REJECTED else TokenError.NETWORK
            } catch (_: IOException) {
                TokenError.NETWORK
            }
            _token.value = TokenUiState(
                present = GitHubToken.get() != null,
                limit = GitHubToken.limit(getApplication()),
                error = failure
            )
            // What the network refused a moment ago may go through now.
            if (failure == null) refresh()
        }
    }

    fun removeToken() {
        GitHubToken.clear(getApplication())
        _token.value = TokenUiState()
    }

    fun clearTokenError() = _token.update { it.copy(error = null) }

    private val _transfer = MutableStateFlow(TransferUiState())
    val transfer: StateFlow<TransferUiState> = _transfer.asStateFlow()

    private val _imports = MutableStateFlow(0)
    /** Ticks with each import, so that whatever shows a setting reads it again. */
    val imports: StateFlow<Int> = _imports.asStateFlow()

    /** Writes the sources, the bookmarks and the settings to the file at [uri]. */
    fun exportTo(uri: Uri) {
        _transfer.value = TransferUiState(busy = true)
        viewModelScope.launch {
            val written = try {
                val text = backup().toJson().toString(2)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?.use { it.write(text.toByteArray()) }
                        ?: throw IOException("Nothing to write to")
                }
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Could not export", e)
                false
            }
            _transfer.value = if (written) TransferUiState(exported = true) else TransferUiState(error = TransferError.FAILED)
        }
    }

    /** What a file held: nothing readable, text that is no backup, or a backup. */
    private class Read(val readable: Boolean, val backup: Backup?)

    /** Reads a file written by [exportTo] and takes in what it holds, see [take]. */
    fun importFrom(uri: Uri) {
        _transfer.value = TransferUiState(busy = true)
        viewModelScope.launch {
            val read = try {
                // Read and taken apart off the main thread: a file within the limit can still
                // name tens of thousands of sources.
                withContext(Dispatchers.IO) {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        // One byte past the limit tells a file too large from one just at it.
                        val bytes = ByteArray(Backup.MAX_BYTES + 1)
                        var count = 0
                        while (count < bytes.size) {
                            val n = input.read(bytes, count, bytes.size - count)
                            if (n < 0) break
                            count += n
                        }
                        if (count > Backup.MAX_BYTES) null else String(bytes, 0, count, Charsets.UTF_8)
                    }
                    Read(text != null, text?.let { Backup.fromJson(it) })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Could not import", e)
                Read(false, null)
            }
            val backup = read.backup
            _transfer.value = when {
                !read.readable -> TransferUiState(error = TransferError.FAILED)
                backup == null -> TransferUiState(error = TransferError.NOT_A_BACKUP)
                else -> TransferUiState(imported = take(backup))
            }
            if (backup != null) {
                _imports.update { it + 1 }
                refresh()
            }
        }
    }

    private fun backup(): Backup {
        val context = getApplication<Application>()
        val ui = context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
        return Backup(
            sources = repository.sources.list(),
            bookmarks = bookmarks.keys.value,
            settings = Backup.Settings(
                includeBeta = repository.includeBeta.value,
                includeAuto = repository.includeAuto.value,
                catalogues = CatalogRepository.CATALOGUE_NAMES.associateWith { it in repository.catalogues.value },
                palette = Palette.fromKey(ui.getString(PREF_PALETTE, null)).key,
                black = ui.getBoolean(PREF_BLACK, false),
                material = ui.getBoolean(PREF_MATERIAL, true),
                hideTop = ui.getBoolean(PREF_HIDE_TOP, true),
                sort = ui.getString(PREF_SORT, null) ?: SortOrder.NAME.name,
                autoUpdate = AutoUpdate.read(context)
            ),
            updates = policy.value
        )
    }

    /**
     * Takes in [backup]: its sources and bookmarks join those here, and its settings replace
     * them. Returns how many sources and how many bookmarks were new. The sources are not
     * checked with GitHub again; one that is gone is simply empty, as any other would be.
     */
    private fun take(backup: Backup): Pair<Int, Int> {
        val context = getApplication<Application>()
        val newSources = repository.sources.addAll(backup.sources)
        val newBookmarks = bookmarks.addAll(backup.bookmarks)
        val settings = backup.settings
        settings.includeBeta?.let(repository::setIncludeBeta)
        settings.includeAuto?.let(repository::setIncludeAuto)
        settings.catalogues.forEach { (source, include) -> repository.setCatalogue(source, include) }
        context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE).edit {
            settings.palette?.let { putString(PREF_PALETTE, Palette.fromKey(it).key) }
            settings.black?.let { putBoolean(PREF_BLACK, it) }
            settings.material?.let { putBoolean(PREF_MATERIAL, it) }
            settings.hideTop?.let { putBoolean(PREF_HIDE_TOP, it) }
            settings.sort?.let { putString(PREF_SORT, it) }
        }
        settings.autoUpdate?.let { AutoUpdate.write(context, it) }
        setPolicy(policy.value + backup.updates)
        _sources.update { it.copy(sources = repository.sources.list()) }
        return newSources to newBookmarks
    }

    /**
     * Refreshes the catalogue. A request made while a refresh is running is not dropped: the
     * running one may have started before a change it should cover (a newly added source), so
     * another pass follows it.
     */
    fun refresh() {
        if (refreshing.value) {
            refreshAgain = true
            return
        }
        refreshing.value = true
        viewModelScope.launch {
            try {
                do {
                    refreshAgain = false
                    try {
                        repository.refresh(foreground = true)
                        error.value = null
                    } catch (_: RateLimitedException) {
                        error.value = LoadError.RATE_LIMIT
                    } catch (e: HttpStatusException) {
                        error.value = if (e.code == 401) LoadError.TOKEN else LoadError.NETWORK
                    } catch (_: IOException) {
                        error.value = LoadError.NETWORK
                    }
                } while (refreshAgain)
            } finally {
                // A refresh renames the source of a repository that has moved.
                _sources.update { it.copy(sources = repository.sources.list()) }
                refreshing.update { false }
            }
        }
    }

    fun install(app: StoreApp) = InstallManager.install(getApplication(), app)

    fun installAll(apps: List<StoreApp>) = InstallManager.installAll(getApplication(), apps)

    fun cancelInstall(fullName: String) = InstallManager.cancel(fullName)

    /** Does not offer [app]'s version again; the next one is offered. */
    fun skipVersion(app: StoreApp) = setPolicy(policy.value.skip(app))

    /** Does not offer any update of [packageName] until [offerUpdates]. */
    fun holdUpdates(packageName: String) = setPolicy(policy.value.hold(packageName, true))

    fun offerUpdates(packageName: String) = setPolicy(policy.value.offer(packageName))

    private fun setPolicy(next: UpdatePolicy) {
        next.write(getApplication())
        policy.value = next
    }

    fun dismissFailure(fullName: String) = InstallManager.dismissFailure(fullName)

    /**
     * Called whenever the app comes to the foreground: re-reads the installed versions (the user
     * may be returning from the system uninstall dialog) and brings stars, download counts and
     * releases up to date, unless that was done a moment ago.
     */
    fun onResume() {
        InstallManager.notifyInstalledChanged()
        val now = System.currentTimeMillis()
        if (now - lastResumeRefresh > RESUME_REFRESH_MS) {
            lastResumeRefresh = now
            refresh()
        }
    }

    private companion object {
        const val TAG = "StoreViewModel"
        const val RESUME_REFRESH_MS = 5 * 60 * 1000L
    }
}
