package com.druk.lmplayground.conversation

import android.app.Application
import android.text.format.Formatter
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.druk.llamacpp.LlamaCpp
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaGenerationSession
import com.druk.llamacpp.LlamaModel
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.lmplayground.App
import com.druk.lmplayground.data.ChatMessageEntity
import com.druk.lmplayground.data.ChatRepository
import com.druk.lmplayground.data.ChatSessionEntity
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.round

class ConversationViewModel(val app: Application) : AndroidViewModel(app) {

    private val llamaCpp: LlamaCpp? = (app as? App)?.llamaCpp
    private var llamaModel: LlamaModel? = null
    private var llamaSession: LlamaGenerationSession? = null
    private var generatingJob: Job? = null

    // Keep strong reference to prevent GC from closing the file descriptor
    private var modelFileHandle: StorageRepository.ModelFileHandle? = null

    private val _isGenerating = MutableLiveData(false)
    private val _isModelReady = MutableLiveData(false)
    private val _modelLoadingProgress = MutableLiveData(0f)
    private val _loadedModel = MutableLiveData<ModelInfo?>(null)
    private val _loadedModelStatus = MutableLiveData<String?>(null)
    private val _models = MutableLiveData<List<ModelWithStatus>>(emptyList())
    private val _supportsThinking = MutableLiveData(false)
    private val _thinkingEnabled = MutableLiveData(false)

    private val storagePreferences = StoragePreferences(app)
    val storageRepository = StorageRepository(app, storagePreferences)

    val isGenerating: LiveData<Boolean> = _isGenerating
    val isModelReady: LiveData<Boolean> = _isModelReady
    val modelLoadingProgress: LiveData<Float> = _modelLoadingProgress
    val loadedModel: LiveData<ModelInfo?> = _loadedModel
    val loadedModelStatus: LiveData<String?> = _loadedModelStatus
    val models: LiveData<List<ModelWithStatus>> = _models
    val supportsThinking: LiveData<Boolean> = _supportsThinking
    val thinkingEnabled: LiveData<Boolean> = _thinkingEnabled

    val uiState = ConversationUiState(
        initialMessages = emptyList()
    )

    // Session persistence
    private val chatRepository: ChatRepository? = (app as? App)?.chatRepository
    private val _currentSessionId = MutableLiveData<String?>(null)
    val currentSessionId: LiveData<String?> = _currentSessionId
    val sessions: LiveData<List<ChatSessionEntity>> =
        chatRepository?.getAllSessions() ?: MutableLiveData(emptyList())

    override fun onCleared() {
        val job = generatingJob
        val session = llamaSession
        val model = llamaModel
        val handle = modelFileHandle
        generatingJob = null
        llamaSession = null
        llamaModel = null
        modelFileHandle = null

        CoroutineScope(Dispatchers.Default).launch {
            job?.cancel()
            job?.join()
            session?.destroy()
            model?.unloadModel()
            handle?.close()
        }
        super.onCleared()
    }

    @MainThread
    fun loadModelList() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val modelFiles = storageRepository.getModelFiles()
                val downloadedFilenames = modelFiles.map { it.name }.toSet()
                val customModels = modelFiles
                    .filter { it.name !in ModelInfoProvider.knownFilenames }
                    .mapNotNull { file ->
                        val cached = storagePreferences.getCustomModelMetadata(file.name)
                            ?: return@mapNotNull null
                        if (!cached.second) return@mapNotNull null
                        ModelInfoProvider.createCustomModelInfo(file.name, cached.first, file.sizeBytes)
                    }
                _models.postValue(
                    ModelInfoProvider.getModelsWithStatus(downloadedFilenames, customModels)
                )
            }
        }
    }

    @MainThread
    fun loadModel(modelInfo: ModelInfo) {
        val llamaCpp = llamaCpp ?: return
        _models.postValue(emptyList())
        _isModelReady.postValue(false)

        viewModelScope.launch {
            // Stop any in-flight generation and tear down previous model
            generatingJob?.cancel()
            generatingJob?.join()
            generatingJob = null

            // Capture and null references on main thread to prevent races
            val prevSession = llamaSession
            val prevModel = llamaModel
            val prevHandle = modelFileHandle
            llamaSession = null
            llamaModel = null
            modelFileHandle = null

            withContext(Dispatchers.Default) {
                prevSession?.destroy()
                prevModel?.unloadModel()
            }

            prevHandle?.close()

            withContext(Dispatchers.Default) {
                _modelLoadingProgress.postValue(0f)
                _loadedModel.postValue(modelInfo)
                _thinkingEnabled.postValue(false)
                _supportsThinking.postValue(false)
                _loadedModelStatus.postValue("Loading...")

                val fileHandle = storageRepository.openModelFile(modelInfo.filename)
                if (fileHandle == null) {
                    _loadedModelStatus.postValue("Cannot open file")
                    return@withContext
                }

                modelFileHandle = fileHandle

                val llamaModel = llamaCpp.loadModel(
                    fileHandle.path,
                    object: LlamaProgressCallback {
                        override fun onProgress(progress: Float) {
                            val progressDescription = "${round(100 * progress).toInt()}%"
                            _modelLoadingProgress.postValue(progress)
                            _loadedModelStatus.postValue(progressDescription)
                        }
                    }
                )
                val modelSize = llamaModel.getModelSize()
                val modelDescription = Formatter.formatFileSize(app, modelSize)
                val llamaSession = llamaModel.createSession()
                this@ConversationViewModel.llamaModel = llamaModel
                this@ConversationViewModel.llamaSession = llamaSession
                _supportsThinking.postValue(llamaModel.supportsThinking())
                _modelLoadingProgress.postValue(0f)
                _loadedModelStatus.postValue(modelDescription)
                _isModelReady.postValue(true)

                // If there are existing messages, replay history into the new session
                val messages = uiState.messages
                if (messages.isNotEmpty()) {
                    replayHistoryToSession(llamaSession, messages)
                }

                // Update session model info if we have an active session
                val sessionId = _currentSessionId.value
                if (sessionId != null) {
                    chatRepository?.updateSessionModel(
                        sessionId, modelInfo.filename, modelInfo.name
                    )
                }
            }
        }
    }

    private fun replayHistoryToSession(session: LlamaGenerationSession, messages: List<Message>) {
        val userMessages = mutableListOf<String>()
        val assistantMessages = mutableListOf<String>()

        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            if (msg.author == "User" && i + 1 < messages.size && messages[i + 1].author == "Assistant") {
                userMessages.add(msg.content)
                assistantMessages.add(messages[i + 1].content)
                i += 2
            } else {
                i++
            }
        }

        if (userMessages.isNotEmpty()) {
            session.replayHistory(
                userMessages.toTypedArray(),
                assistantMessages.toTypedArray()
            )
        }
    }

    @MainThread
    fun toggleThinking() {
        _thinkingEnabled.value = _thinkingEnabled.value != true
    }

    @MainThread
    fun addMessage(message: Message) {
        uiState.addMessage(message)

        val enableThinking = _thinkingEnabled.value == true
        uiState.addMessage(
            Message(
                "Assistant",
                "",
                thinkingStartTimeMs = if (enableThinking) System.currentTimeMillis() else 0L
            )
        )

        _isGenerating.postValue(true)
        generatingJob = viewModelScope.launch {
            // Persist user message
            val sessionId = ensureSession(message)
            persistMessage(sessionId, message)

            withContext(Dispatchers.Default) {
                val llamaSession = llamaSession ?: return@withContext
                llamaSession.addMessage(message.content, enableThinking)

                val callback = object: LlamaGenerationCallback {
                    var responseByteArray = ByteArray(0)
                    var totalTokens = 0
                    var thinkingTokenCount = 0
                    var thinkingComplete = !enableThinking
                    var modelIsThinking = enableThinking
                    override fun newTokens(newTokens: ByteArray) {
                        responseByteArray += newTokens
                        totalTokens++
                        var string = String(responseByteArray, Charsets.UTF_8)
                        string = ResponseProcessor.process(string)

                        // Detect thinking from model output even when the
                        // toggle is off (models like LFM 2.5 always think)
                        if (!modelIsThinking && string.startsWith("<think>")) {
                            modelIsThinking = true
                            thinkingComplete = false
                            uiState.markThinkingStarted()
                        }

                        if (modelIsThinking) {
                            string = ResponseProcessor.ensureThinkingTag(string)
                        } else {
                            string = ResponseProcessor.stripCompleteThinkBlocks(string)
                        }
                        if (!thinkingComplete && string.contains("</think>")) {
                            thinkingComplete = true
                            thinkingTokenCount = totalTokens
                        }
                        val currentThinkingTokens = if (thinkingComplete) thinkingTokenCount else totalTokens
                        uiState.updateLastMessage(
                            string,
                            thinkingTokens = currentThinkingTokens,
                            responseTokens = totalTokens - currentThinkingTokens
                        )
                    }
                }
                while (this.isActive && llamaSession.generate(callback) == 0) {
                    // wait for the response
                }
                llamaSession.printReport()
                _isGenerating.postValue(false)

                // Persist assistant response
                val assistantMessage = uiState.messages.lastOrNull()
                if (assistantMessage != null && assistantMessage.author == "Assistant") {
                    persistMessage(sessionId, assistantMessage)
                    chatRepository?.updateSessionTimestamp(sessionId, System.currentTimeMillis())
                }
            }
        }
    }

    private suspend fun ensureSession(firstUserMessage: Message): String {
        val existing = _currentSessionId.value
        if (existing != null) return existing

        val modelInfo = _loadedModel.value
        val id = UUID.randomUUID().toString()
        val title = firstUserMessage.content.take(50)
        val now = System.currentTimeMillis()
        chatRepository?.insertSession(
            ChatSessionEntity(
                id = id,
                title = title,
                modelFilename = modelInfo?.filename ?: "",
                modelName = modelInfo?.name ?: "Unknown",
                createdAt = now,
                updatedAt = now
            )
        )
        _currentSessionId.postValue(id)
        return id
    }

    private suspend fun persistMessage(sessionId: String, message: Message) {
        chatRepository?.insertMessage(
            ChatMessageEntity(
                sessionId = sessionId,
                author = message.author,
                content = message.content,
                thinkingDurationSeconds = message.thinkingDurationSeconds,
                thinkingTokens = message.thinkingTokens,
                responseTokens = message.responseTokens,
                timestamp = message.timestamp
            )
        )
    }

    @MainThread
    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val messages = chatRepository?.getMessages(sessionId) ?: return@launch
            val uiMessages = messages.map { entity ->
                Message(
                    author = entity.author,
                    content = entity.content,
                    thinkingDurationSeconds = entity.thinkingDurationSeconds,
                    thinkingTokens = entity.thinkingTokens,
                    responseTokens = entity.responseTokens,
                    timestamp = entity.timestamp
                )
            }
            _currentSessionId.value = sessionId
            uiState.setMessages(uiMessages)

            // Replay history into native session if model is loaded
            val session = llamaSession
            if (session != null) {
                withContext(Dispatchers.Default) {
                    replayHistoryToSession(session, uiMessages)
                }
            }
        }
    }

    @MainThread
    fun newConversation() {
        viewModelScope.launch {
            generatingJob?.cancel()
            generatingJob?.join()
            generatingJob = null

            _currentSessionId.value = null
            uiState.resetMessages()

            // Recreate native session with clean KV cache
            val model = llamaModel
            if (model != null) {
                val prevSession = llamaSession
                llamaSession = null
                withContext(Dispatchers.Default) {
                    prevSession?.destroy()
                    val newSession = model.createSession()
                    this@ConversationViewModel.llamaSession = newSession
                }
            }
        }
    }

    @MainThread
    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            chatRepository?.updateSessionTitle(sessionId, newTitle)
        }
    }

    @MainThread
    fun pinSession(sessionId: String, pinned: Boolean) {
        viewModelScope.launch {
            chatRepository?.updateSessionPinned(sessionId, pinned)
        }
    }

    @MainThread
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            chatRepository?.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
                uiState.resetMessages()
            }
        }
    }

    @MainThread
    fun cancelGeneration() {
        generatingJob?.cancel()
    }

    fun getReport(): String? {
        val modelReport = llamaModel?.getModelReport() ?: return null
        val sessionReport = llamaSession?.getReport() ?: return null
        return modelReport + "\n" + sessionReport
    }

    fun unloadModel() {
        viewModelScope.launch {
            if (modelFileHandle != null || llamaModel != null) {
                generatingJob?.cancel()
                generatingJob?.join()
                generatingJob = null

                // Capture and null references on main thread to prevent races
                val prevSession = llamaSession
                val prevModel = llamaModel
                val prevHandle = modelFileHandle
                llamaSession = null
                llamaModel = null
                modelFileHandle = null

                withContext(Dispatchers.Default) {
                    prevSession?.destroy()
                    prevModel?.unloadModel()
                }

                prevHandle?.close()

                _loadedModel.postValue(null)
                _loadedModelStatus.postValue(null)
                _isModelReady.postValue(false)
                _supportsThinking.postValue(false)
            }
        }
    }

    fun resetModelList() {
        _models.postValue(emptyList())
    }

}
