package com.druk.lmplayground

import android.app.Application
import com.druk.llamacpp.LlamaCpp
import com.druk.lmplayground.download.DownloadNotificationManager

class App: Application() {

    val llamaCpp = LlamaCpp()

    override fun onCreate() {
        super.onCreate()
        llamaCpp.init()
        DownloadNotificationManager.createChannel(this)
    }
}