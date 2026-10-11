package org.jarsi.arkstore.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jarsi.arkstore.backup.BackupCandidate
import org.jarsi.arkstore.backup.BackupFolder
import org.jarsi.arkstore.backup.BackupManager
import org.jarsi.arkstore.backup.BackupState
import org.jarsi.arkstore.backup.Installers
import org.jarsi.arkstore.backup.Selection

/**
 * What the pages of the backup show and hold across their recreation: the folder, the apps
 * found on the device and chosen for a backup, and the apps found in the folder and chosen
 * for a restore. The work itself is [BackupManager]'s, whose [state] the pages watch.
 *
 * Each list is chosen by a rule (not from Google Play; missing or behind on the device) unless
 * the user has said otherwise about an app, see [Selection]: a list read again keeps what the
 * user chose and dropped, and applies the rule to the rest.
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()

    val state: StateFlow<BackupState> = BackupManager.state

    private val _tree = MutableStateFlow(BackupManager.rememberedTree(context))
    /** The folder chosen, when the right to it is held. */
    val tree: StateFlow<Uri?> = _tree.asStateFlow()

    private val _candidates = MutableStateFlow<List<BackupCandidate>?>(null)
    /** The apps a backup may take, or null until they have been read. */
    val candidates: StateFlow<List<BackupCandidate>?> = _candidates.asStateFlow()
    private var candidatesJob: Job? = null
    private var backupSaid = Selection()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    /** The packages chosen for the backup. */
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _folder = MutableStateFlow<BackupFolder?>(null)
    /** The folder as read for a restore, or null while it is being read or has not been. */
    val folder: StateFlow<BackupFolder?> = _folder.asStateFlow()
    private var readJob: Job? = null
    private val refreshes = AtomicInteger(0)

    private val _reading = MutableStateFlow(false)
    val reading: StateFlow<Boolean> = _reading.asStateFlow()

    private val _folderError = MutableStateFlow(false)
    /** Whether the folder could not be listed at all. */
    val folderError: StateFlow<Boolean> = _folderError.asStateFlow()

    private var restoreSaid = Selection()

    private val _restoreSelected = MutableStateFlow<Set<String>>(emptySet())
    /** The packages chosen for the restore. */
    val restoreSelected: StateFlow<Set<String>> = _restoreSelected.asStateFlow()

    private val _importSettings = MutableStateFlow(true)
    /** Whether the store's settings file in the folder is brought back too. */
    val importSettings: StateFlow<Boolean> = _importSettings.asStateFlow()

    init {
        // Once a restore is over, the rule chooses afresh: an app just brought back is not
        // chosen again for the next try, whatever was said before.
        viewModelScope.launch {
            BackupManager.state.collect {
                if (it is BackupState.Done && it.restoring) {
                    restoreSaid = Selection()
                    refreshStatuses()
                }
            }
        }
    }

    /**
     * Takes [uri] as the folder, for this time and the next. A read of the folder before is
     * given up; the restore page reads the new one when shown.
     */
    fun chooseTree(uri: Uri) {
        BackupManager.rememberTree(context, uri)
        readJob?.cancel()
        _reading.value = false
        _folder.value = null
        _folderError.value = false
        restoreSaid = Selection()
        _tree.value = uri
    }

    /**
     * Reads the installed apps afresh: the versions and files change with every install. The
     * user's choices stand; an app new to the list follows the rule.
     */
    fun loadCandidates() {
        if (candidatesJob?.isActive == true) return
        candidatesJob = viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { BackupManager.candidates(context) }
            _candidates.value = list
            chooseForBackup(list)
        }
    }

    private fun chooseForBackup(list: List<BackupCandidate>) {
        _selected.value = Selection.chosen(
            list.map { it.entry.packageName to Installers.preselected(it.entry.installer) },
            list.filter { it.readable }.mapTo(HashSet()) { it.entry.packageName },
            backupSaid
        )
    }

    fun toggle(packageName: String) {
        backupSaid = backupSaid.toggled(packageName, packageName !in _selected.value)
        _candidates.value?.let(::chooseForBackup)
    }

    /** Chooses every app whose files can be read, or none. */
    fun selectAll(all: Boolean) {
        val list = _candidates.value ?: return
        backupSaid = backupSaid.all(list.filter { it.readable }.map { it.entry.packageName }, all)
        chooseForBackup(list)
    }

    /** Begins the backup of the chosen apps, with [settingsJson] alongside; false when nothing was begun. */
    fun startBackup(settingsJson: String): Boolean {
        val tree = _tree.value ?: return false
        val chosen = _candidates.value.orEmpty().filter { it.readable && it.entry.packageName in _selected.value }
        if (chosen.isEmpty()) return false
        return BackupManager.backup(context, tree, chosen, settingsJson)
    }

    /**
     * Reads the folder for a restore, afresh each time. What is read is kept only if the
     * folder is still the one chosen: a folder chosen meanwhile has a read of its own.
     */
    fun loadFolder() {
        val tree = _tree.value ?: return
        readJob?.cancel()
        _reading.value = true
        readJob = viewModelScope.launch {
            val read = BackupManager.read(context, tree)
            if (_tree.value != tree) return@launch
            _folderError.value = read == null
            _folder.value = read
            if (read != null) chooseForRestore(read)
            _reading.value = false
        }
    }

    /** Sees the folder read against what is installed now, after an install has finished. */
    fun refreshStatuses() {
        val folder = _folder.value ?: return
        val turn = refreshes.incrementAndGet()
        viewModelScope.launch {
            val seen = BackupManager.refresh(context, folder)
            // Only the latest look is kept, and only for the folder still chosen.
            if (turn != refreshes.get() || _tree.value != seen.tree || _folder.value?.tree != seen.tree) return@launch
            _folder.value = seen
            chooseForRestore(seen)
        }
    }

    private fun chooseForRestore(folder: BackupFolder) {
        _restoreSelected.value = Selection.chosen(
            folder.entries.map { it.entry.packageName to it.status.preselected },
            folder.entries.filter { it.status.selectable }.mapTo(HashSet()) { it.entry.packageName },
            restoreSaid
        )
    }

    fun toggleRestore(packageName: String) {
        restoreSaid = restoreSaid.toggled(packageName, packageName !in _restoreSelected.value)
        _folder.value?.let(::chooseForRestore)
    }

    fun selectAllRestore(all: Boolean) {
        val folder = _folder.value ?: return
        restoreSaid = restoreSaid.all(folder.entries.filter { it.status.selectable }.map { it.entry.packageName }, all)
        chooseForRestore(folder)
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
