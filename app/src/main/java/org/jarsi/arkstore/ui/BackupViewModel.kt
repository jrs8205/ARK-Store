package org.jarsi.arkstore.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jarsi.arkstore.backup.BackupCandidate
import org.jarsi.arkstore.backup.BackupFolder
import org.jarsi.arkstore.backup.BackupManager
import org.jarsi.arkstore.backup.BackupState
import org.jarsi.arkstore.backup.Installers

/**
 * What the pages of the backup show and hold across their recreation: the folder, the apps
 * found on the device and chosen for a backup, and the apps found in the folder and chosen
 * for a restore. The work itself is [BackupManager]'s, whose [state] the pages watch.
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()

    val state: StateFlow<BackupState> = BackupManager.state

    private val _tree = MutableStateFlow(BackupManager.rememberedTree(context))
    /** The folder chosen, when the right to it is held. */
    val tree: StateFlow<Uri?> = _tree.asStateFlow()

    private val _candidates = MutableStateFlow<List<BackupCandidate>?>(null)
    /** The apps a backup may take, or null while they are being read. */
    val candidates: StateFlow<List<BackupCandidate>?> = _candidates.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    /** The packages chosen for the backup. */
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()
    private var preselected = false

    private val _folder = MutableStateFlow<BackupFolder?>(null)
    /** The folder as read for a restore, or null while it is being read or has not been. */
    val folder: StateFlow<BackupFolder?> = _folder.asStateFlow()

    private val _reading = MutableStateFlow(false)
    val reading: StateFlow<Boolean> = _reading.asStateFlow()

    private val _folderError = MutableStateFlow(false)
    /** Whether the folder could not be listed at all. */
    val folderError: StateFlow<Boolean> = _folderError.asStateFlow()

    private val _restoreSelected = MutableStateFlow<Set<String>>(emptySet())
    /** The packages chosen for the restore. */
    val restoreSelected: StateFlow<Set<String>> = _restoreSelected.asStateFlow()
    private var restorePreselected = false

    private val _importSettings = MutableStateFlow(true)
    /** Whether the store's settings file in the folder is brought back too. */
    val importSettings: StateFlow<Boolean> = _importSettings.asStateFlow()

    /** Takes [uri] as the folder, for this time and the next, and reads it afresh. */
    fun chooseTree(uri: Uri) {
        BackupManager.rememberTree(context, uri)
        _tree.value = uri
        _folder.value = null
        _folderError.value = false
        restorePreselected = false
        loadFolder()
    }

    /** Reads the installed apps, once; those not from Google Play are chosen to begin with. */
    fun loadCandidates() {
        if (_candidates.value != null) return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { BackupManager.candidates(context) }
            _candidates.value = list
            if (!preselected) {
                preselected = true
                _selected.value = list
                    .filter { it.readable && Installers.preselected(it.entry.installer) }
                    .mapTo(HashSet()) { it.entry.packageName }
            }
        }
    }

    fun toggle(packageName: String) {
        _selected.update { if (packageName in it) it - packageName else it + packageName }
    }

    /** Chooses every app whose files can be read, or none. */
    fun selectAll(all: Boolean) {
        _selected.value = if (all) {
            _candidates.value.orEmpty().filter { it.readable }.mapTo(HashSet()) { it.entry.packageName }
        } else {
            emptySet()
        }
    }

    /** Begins the backup of the chosen apps, with [settingsJson] alongside; false when nothing was begun. */
    fun startBackup(settingsJson: String): Boolean {
        val tree = _tree.value ?: return false
        val chosen = _candidates.value.orEmpty().filter { it.readable && it.entry.packageName in _selected.value }
        if (chosen.isEmpty()) return false
        return BackupManager.backup(context, tree, chosen, settingsJson)
    }

    /**
     * Reads the folder for a restore, unless it has been read ([force] reads it again). What is
     * missing or behind on the device is chosen the first time; later reads keep the choice,
     * less what can no longer be chosen.
     */
    fun loadFolder(force: Boolean = false) {
        val tree = _tree.value ?: return
        if (_folder.value != null && !force) return
        if (_reading.value) return
        _reading.value = true
        viewModelScope.launch {
            val read = BackupManager.read(context, tree)
            _folderError.value = read == null
            _folder.value = read
            if (read != null) chooseForRestore(read)
            _reading.value = false
        }
    }

    /** Sees the folder read against what is installed now, after an install has finished. */
    fun refreshStatuses() {
        val folder = _folder.value ?: return
        viewModelScope.launch {
            val seen = BackupManager.refresh(context, folder)
            _folder.value = seen
            chooseForRestore(seen)
        }
    }

    private fun chooseForRestore(folder: BackupFolder) {
        if (!restorePreselected) {
            restorePreselected = true
            _restoreSelected.value = folder.entries
                .filter { it.status.preselected }
                .mapTo(HashSet()) { it.entry.packageName }
        } else {
            val selectable = folder.entries.filter { it.status.selectable }.mapTo(HashSet()) { it.entry.packageName }
            _restoreSelected.update { it.filterTo(HashSet()) { name -> name in selectable } }
        }
    }

    fun toggleRestore(packageName: String) {
        _restoreSelected.update { if (packageName in it) it - packageName else it + packageName }
    }

    fun selectAllRestore(all: Boolean) {
        _restoreSelected.value = if (all) {
            _folder.value?.entries.orEmpty().filter { it.status.selectable }.mapTo(HashSet()) { it.entry.packageName }
        } else {
            emptySet()
        }
    }

    fun setImportSettings(import: Boolean) {
        _importSettings.value = import
    }

    /**
     * Begins the restore of the chosen apps; the settings file goes to [onImportSettings] first
     * when it is there and wanted. False when nothing was begun.
     */
    fun startRestore(onImportSettings: (Uri) -> Unit): Boolean {
        val folder = _folder.value ?: return false
        val chosen = folder.entries.filter { it.status.selectable && it.entry.packageName in _restoreSelected.value }
        val settings = folder.settings
        if (_importSettings.value && settings != null) onImportSettings(settings)
        if (chosen.isEmpty()) return settings != null && _importSettings.value
        return BackupManager.restore(context, folder, chosen)
    }

    fun cancel() = BackupManager.cancel()

    fun dismiss() = BackupManager.dismiss()
}
