package org.jarsi.arkstore

import android.app.Application
import org.jarsi.arkstore.work.UpdateCheckWorker

class ArkStoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateCheckWorker.createChannel(this)
        UpdateCheckWorker.schedule(this)
    }
}
