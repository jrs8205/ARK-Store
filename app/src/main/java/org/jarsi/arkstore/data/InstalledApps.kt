package org.jarsi.arkstore.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat

data class InstalledVersion(val versionCode: Long, val versionName: String?)

enum class AppStatus { NOT_INSTALLED, UPDATE_AVAILABLE, UP_TO_DATE }

object InstalledApps {

    fun find(context: Context, packageName: String?): InstalledVersion? {
        packageName ?: return null
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            InstalledVersion(PackageInfoCompat.getLongVersionCode(info), info.versionName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun status(app: StoreApp, installed: InstalledVersion?): AppStatus = when {
        installed == null -> AppStatus.NOT_INSTALLED
        app.versionCode > installed.versionCode -> AppStatus.UPDATE_AVAILABLE
        else -> AppStatus.UP_TO_DATE
    }

    fun countUpdates(context: Context, apps: List<StoreApp>): List<StoreApp> = apps.filter {
        status(it, find(context, it.packageName)) == AppStatus.UPDATE_AVAILABLE
    }
}
