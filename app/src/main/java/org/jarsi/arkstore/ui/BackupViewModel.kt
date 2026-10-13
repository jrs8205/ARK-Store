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
import org.jarsi.arkstore.backup.DataChoice
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
/** Numbers the reads of something, so that only the latest is taken when several overlap. */
internal class Turns {
    private val count = AtomicInteger(0)

    fun next(): Int = count.incrementAndGet()

    fun isLatest(turn: Int): Boolean = count.get() == turn
}

class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()

    val state: StateFlow<BackupState> = BackupManager.state

    private val _tree = MutableStateFlow(BackupManager.rememberedTree(context))
    /** The folder chosen, when the right to it is held. */
    val tree: StateFlow<Uri?> = _tree.asStateFlow()

    private val _readRequests = MutableStateFlow(0)
    /** Ticks at every choice of a folder or a zip file, the same one too, for the restore page to read it. */
    val readRequests: StateFlow<Int> = _readRequests.asStateFlow()

    private val _zip = MutableStateFlow<Uri?>(null)
    /** The zip file chosen to restore from, which stands before the folder while it is chosen. */
    val zip: StateFlow<Uri?> = _zip.asStateFlow()

    private val _candidates = MutableStateFlow<List<BackupCandidate>?>(null)
    /** The apps a backup may take, or null until they have been read. */
    val candidates: StateFlow<List<BackupCandidate>?> = _candidates.asStateFlow()
    private var candidatesJob: Job? = null
    private val candidateTurns = Turns()
    private var backupSaid = Selection()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    /** The packages chosen for the backup. */
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _folder = MutableStateFlow<BackupFolder?>(null)
    /** The folder as read for a restore, or null while it is being read or has not been. */
    val folder: StateFlow<BackupFolder?> = _folder.asStateFlow()
    private var readJob: Job? = null
    private val refreshes = Turns()
    /** An install finished while the folder was being read; its statuses are looked at again after. */
    private var refreshPending = false

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

    private val _dataAvailable = MutableStateFlow(false)
    /** Whether the apps' own data can be taken and put back: root is on and the device has a `tar`. */
    val dataAvailable: StateFlow<Boolean> = _dataAvailable.asStateFlow()

    private val _includeData = MutableStateFlow(true)
    /** Whether the backup takes the apps' own data, when it can. */
    val includeData: StateFlow<Boolean> = _includeData.asStateFlow()

    private val _password = MutableStateFlow("")
    /** The password the data is encrypted with; empty for none. */
    val password: StateFlow<String> = _password.asStateFlow()

    private val _restoreData = MutableStateFlow(true)
    /** Whether the restore puts the apps' own data back, when the backup has it and it can. */
    val restoreData: StateFlow<Boolean> = _restoreData.asStateFlow()

    private val _restorePassword = MutableStateFlow("")
    /** The password the backup's data was encrypted with. */
    val restorePassword: StateFlow<String> = _restorePassword.asStateFlow()

    fun setIncludeData(include: Boolean) {
        _includeData.value = include
    }

    fun setPassword(password: String) {
        _password.value = password
    }

    fun setRestoreData(restore: Boolean) {
        _restoreData.value = restore
    }

    fun setRestorePassword(password: String) {
        _restorePassword.value = password
    }

    /** Asks whether the data can be taken, afresh: root may have been turned on or off meanwhile. */
    fun checkDataAvailable() {
        viewModelScope.launch { _dataAvailable.value = BackupManager.dataAvailable(context) }
    }

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
        forgetRead()
        _zip.value = null
        _tree.value = uri
    }

    /** Takes [uri] as the zip file to restore from; the restore page reads it when shown. */
    fun chooseZip(uri: Uri) {
        forgetRead()
        _zip.value = uri
    }

    private fun forgetRead() {
        _readRequests.value++
        readJob?.cancel()
        _reading.value = false
        _folder.value = null
        _folderError.value = false
        restoreSaid = Selection()
    }

    /**
     * Reads the installed apps afresh: the versions and files change with every install. A read
     * still on its way is given up for this one, which sees the latest install. The user's
     * choices stand; an app new to the list follows the rule.
     */
    fun loadCandidates() {
        val turn = candidateTurns.next()
        candidatesJob?.cancel()
        candidatesJob = viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { BackupManager.candidates(context) }
            if (!candidateTurns.isLatest(turn)) return@launch
            _candidates.value = list
            chooseForBackup(list)
            // The sizes of the data come from root, and take a moment; the list stands without them meanwhile.
            val available = BackupManager.dataAvailable(context)
            _dataAvailable.value = available
            if (!available) return@launch
            val sizes = BackupManager.dataSizes(list.map { it.entry.packageName })
            if (!candidateTurns.isLatest(turn)) return@launch
            _candidates.value = list.map { it.copy(dataSize = sizes[it.entry.packageName]) }
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
        val chosen = chosenForBackup().ifEmpty { return false }
        return BackupManager.backup(context, tree, chosen, settingsJson, dataChoice())
    }

    /** Begins writing the chosen apps to the zip file [uri], made for it; false when nothing was begun. */
    fun startZipBackup(uri: Uri, settingsJson: String): Boolean {
        val chosen = chosenForBackup().ifEmpty { return false }
        return BackupManager.backupZip(context, uri, chosen, settingsJson, dataChoice())
    }

    /** What the backup does with the data: takes it when it can and the user has not said no. */
    private fun dataChoice(): DataChoice {
        val include = _dataAvailable.value && _includeData.value
        return DataChoice(include, if (include) _password.value.toCharArray() else null)
    }

    private fun chosenForBackup(): List<BackupCandidate> =
        _candidates.value.orEmpty().filter { it.readable && it.entry.packageName in _selected.value }

    /**
     * Reads the backup for a restore, the zip file when one is chosen and the folder otherwise,
     * afresh each time. What is read is kept only if it is still the one chosen: one chosen
     * meanwhile has a read of its own.
     */
    fun loadFolder() {
        val zip = _zip.value
        val source = zip ?: _tree.value ?: return
        readJob?.cancel()
        _reading.value = true
        readJob = viewModelScope.launch {
            val read = if (zip != null) BackupManager.readZip(context, zip) else BackupManager.read(context, source)
            if ((_zip.value ?: _tree.value) != source) return@launch
            _folderError.value = read == null
            _folder.value = read
            if (read != null) chooseForRestore(read)
            _reading.value = false
            if (refreshPending) {
                refreshPending = false
                refreshStatuses()
            }
        }
    }

    /**
     * Sees the folder read against what is installed now, after an install has finished. The
     * look is kept only if it is the latest and the folder read is still the one it began from:
     * a read of the folder since then has the newer content, and comes first.
     */
    fun refreshStatuses() {
        if (_reading.value) {
            refreshPending = true
            return
        }
        val folder = _folder.value ?: return
        val turn = refreshes.next()
        viewModelScope.launch {
            val seen = BackupManager.refresh(context, folder)
            if (!refreshes.isLatest(turn) || _folder.value !== folder) return@launch
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
        val include = _dataAvailable.value && _restoreData.value && chosen.any { it.entry.data.isNotEmpty() }
        val data = DataChoice(include, if (include) _restorePassword.value.toCharArray() else null)
        return BackupManager.restore(context, folder, chosen, data)
    }

    fun cancel() = BackupManager.cancel()

    fun dismiss() = BackupManager.dismiss()
}
