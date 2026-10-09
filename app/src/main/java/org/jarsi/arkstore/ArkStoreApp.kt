package org.jarsi.arkstore

import android.app.Application
import org.jarsi.arkstore.data.GitHubToken
import org.jarsi.arkstore.install.InstallService
import org.jarsi.arkstore.work.UpdateCheckWorker

class ArkStoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GitHubToken.load(this)
        UpdateCheckWorker.createChannel(this)
        InstallService.createChannels(this)
        UpdateCheckWorker.schedule(this)
    }
}
