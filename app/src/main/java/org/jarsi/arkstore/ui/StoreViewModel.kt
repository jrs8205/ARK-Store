package org.jarsi.arkstore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jarsi.arkstore.data.AppStatus
import org.jarsi.arkstore.data.CatalogRepository
import org.jarsi.arkstore.data.HttpStatusException
import org.jarsi.arkstore.data.InstalledApps
import org.jarsi.arkstore.data.InstalledVersion
import org.jarsi.arkstore.data.RateLimitedException
import org.jarsi.arkstore.data.SourceStore
import org.jarsi.arkstore.data.StoreApp
import org.jarsi.arkstore.install.InstallManager

data class AppRow(val app: StoreApp, val installed: InstalledVersion?, val status: AppStatus) {
    /**
     * A newer version than the one offered is installed, typically a beta after beta versions
     * were turned off. Android cannot put the older version over it.
     */
    val newerInstalled: Boolean
        get() = installed != null && app.versionCode > 0 && installed.versionCode > app.versionCode
}

enum class LoadError { NETWORK, RATE_LIMIT }

enum class SourceError { INVALID, DUPLICATE, NOT_FOUND, NOT_OPEN, NETWORK, RATE_LIMIT }

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

    val state: StateFlow<StoreUiState> = combine(
        repository.catalog,
        InstallManager.installedChanged,
        refreshing,
        error
    ) { catalog, _, isRefreshing, loadError ->
        StoreUiState(
            rows = catalog.apps.map { app ->
                val installed = InstalledApps.find(application, app.packageName)
                AppRow(app, installed, InstalledApps.status(app, installed))
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
        viewModelScope.launch { repository.setIncludeAuto(include) }
    }

    fun setIncludeBeta(include: Boolean) {
        viewModelScope.launch { repository.setIncludeBeta(include) }
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
                if (e.code == 404) SourceError.NOT_FOUND else SourceError.NETWORK
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

    fun installAll(apps: List<StoreApp>) = apps.forEach(::install)

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
        const val RESUME_REFRESH_MS = 5 * 60 * 1000L
    }
}
