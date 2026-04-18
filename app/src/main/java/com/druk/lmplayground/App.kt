package com.druk.lmplayground

import android.app.Application
import com.druk.llamacpp.LlamaCpp
import com.druk.lmplayground.data.AppDatabase
import com.druk.lmplayground.data.ChatRepository
import com.druk.lmplayground.data.SystemPromptRepository
import com.druk.lmplayground.download.DownloadNotificationManager

class App: Application() {

    val llamaCpp = LlamaCpp()
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var systemPromptRepository: SystemPromptRepository
        private set

    override fun onCreate() {
        super.onCreate()
        llamaCpp.init()
        DownloadNotificationManager.createChannel(this)
        val database = AppDatabase.getInstance(this)
        chatRepository = ChatRepository(database.chatDao())
        systemPromptRepository = SystemPromptRepository(database.systemPromptDao())
    }
}
