package org.jarsi.arkstore

import android.app.Application
import org.jarsi.arkstore.install.InstallService
import org.jarsi.arkstore.work.UpdateCheckWorker

class ArkStoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateCheckWorker.createChannel(this)
        InstallService.createChannel(this)
        UpdateCheckWorker.schedule(this)
    }
}
