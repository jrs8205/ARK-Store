package org.jarsi.arkstore.backup

import org.jarsi.arkstore.data.CatalogRules
import org.jarsi.arkstore.data.StoreApp

/** Where a restore without root takes an app's file from, in the order tried. */
enum class RestoreSource {
    /** The catalogue's newest version for this device, downloaded and checked. */
    CATALOGUE,
    /** The backup's own file, when the app is in one file that Android can install. */
    BACKUP
}

/** Why a restore without root left an app out. */
enum class LeftOut { SPLIT, FILES_MISSING, NEEDS_NEWER_ANDROID, OTHER_SIGNER, FAILED }

/**
 * The rules of a restore without root, which installs nothing: it gathers each app's file in a
 * folder for the user to install, the catalogue's newest when it has one for this device and
 * the backup's own otherwise.
 */
internal object RestorePlan {

    /**
     * The catalogue's newest version of [entry]'s app for a device running [android], among
     * [apps]: a full release, of the project whose key the backup has when that is known, that
     * runs here and is not signed with a key the backup does not have. One whose key the
     * catalogue does not tell is tried, and its key compared once it is downloaded.
     */
    fun offer(entry: BackupEntry, apps: List<StoreApp>, android: CatalogRules.Android): StoreApp? {
        val offers = apps.filter { it.packageName == entry.packageName && !it.prerelease }
        if (offers.isEmpty()) return null
        val signers = entry.signers.toSet().ifEmpty { null }
        return CatalogRules.merged(offers, android) { signers }
            .filter { !it.otherBuild && CatalogRules.runsOn(it.app, android) }
            .map { it.app }
            .firstOrNull { signers == null || it.signer == null || CatalogRules.sameSigner(it.signer, signers) }
    }

    /**
     * Where [entry]'s file is taken from, in turn, standing as [status]: the catalogue's [offer]
     * first, then the backup's own file when the app is in one file and the backup has it whole
     * for this device. A split app cannot be installed from a file browser.
     */
    fun sources(entry: BackupEntry, status: RestoreStatus, offer: StoreApp?): List<RestoreSource> = buildList {
        if (offer != null) add(RestoreSource.CATALOGUE)
        if (entry.files.size == 1 && status != RestoreStatus.FILES_MISSING && status != RestoreStatus.NEEDS_NEWER_ANDROID) {
            add(RestoreSource.BACKUP)
        }
    }

    /**
     * Whether a restore without root may take [entry]: one the backup could restore, or one
     * whose files are missing or need a newer Android but which the catalogue offers; either
     * way, only with somewhere to take the file from.
     */
    fun selectableWithoutRoot(entry: BackupEntry, status: RestoreStatus, offer: StoreApp?): Boolean =
        (status.selectable || status == RestoreStatus.FILES_MISSING || status == RestoreStatus.NEEDS_NEWER_ANDROID) &&
            sources(entry, status, offer).isNotEmpty()

    /** Why [entry], standing as [status], was left out, the catalogue's file having been [triedCatalogue] or not. */
    fun leftOutReason(entry: BackupEntry, status: RestoreStatus, triedCatalogue: Boolean): LeftOut = when {
        triedCatalogue -> LeftOut.FAILED
        status == RestoreStatus.FILES_MISSING -> LeftOut.FILES_MISSING
        status == RestoreStatus.NEEDS_NEWER_ANDROID -> LeftOut.NEEDS_NEWER_ANDROID
        entry.files.size > 1 -> LeftOut.SPLIT
        else -> LeftOut.FAILED
    }
}
