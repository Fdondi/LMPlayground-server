package com.druk.lmplayground

import android.app.Application
import android.content.ComponentName
import com.druk.llamacpp.InferenceClient
import com.druk.llamacpp.LlamaCpp
import com.druk.lmplayground.data.AppDatabase
import com.druk.lmplayground.data.ChatRepository
import com.druk.lmplayground.data.SystemPromptRepository
import com.druk.lmplayground.download.DownloadNotificationManager
import com.druk.lmplayground.inference.LlamaService
import com.druk.lmplayground.inference.ProcessUtils

class App : Application() {

    lateinit var inferenceClient: InferenceClient
        private set
    lateinit var llamaCpp: LlamaCpp
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var systemPromptRepository: SystemPromptRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // App.onCreate runs in *every* process the app spawns. The :llama
        // process only needs to host LlamaService — skip Room, repos, and
        // the inference-client binding (no service to bind from there).
        if (ProcessUtils.isLlamaProcess()) return

        inferenceClient = InferenceClient(
            appContext = applicationContext,
            serviceComponent = ComponentName(this, LlamaService::class.java),
        )
        inferenceClient.bind()
        llamaCpp = LlamaCpp(inferenceClient)

        DownloadNotificationManager.createChannel(this)
        val database = AppDatabase.getInstance(this)
        chatRepository = ChatRepository(database.chatDao())
        systemPromptRepository = SystemPromptRepository(database.systemPromptDao())
    }
}
