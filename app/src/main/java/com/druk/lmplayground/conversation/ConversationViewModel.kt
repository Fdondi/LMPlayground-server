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
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
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
    private val _generationParams = MutableLiveData(GenerationParams())
    private val _maxContextSize = MutableLiveData(4096)
    private val _sessionModelHint = MutableLiveData<Pair<String, String>?>(null) // (modelName, modelFilename)
    private val _supportsVision = MutableLiveData(false)

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
    val generationParams: LiveData<GenerationParams> = _generationParams
    val maxContextSize: LiveData<Int> = _maxContextSize
    val sessionModelHint: LiveData<Pair<String, String>?> = _sessionModelHint
    val supportsVision: LiveData<Boolean> = _supportsVision

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
                _supportsVision.postValue(false)
                _loadedModelStatus.postValue("Loading...")

                val fileHandle = storageRepository.openModelFile(modelInfo.filename)
                if (fileHandle == null) {
                    _loadedModelStatus.postValue("Cannot open file")
                    return@withContext
                }

                modelFileHandle = fileHandle

                // llama.cpp only reports progress during tensor pointer setup,
                // which is near-instant with mmap. The slow parts (GGUF metadata
                // parsing, mmap init, buffer allocation) report nothing.
                // Animate estimated progress during loading for smooth UX.
                val progressJob = CoroutineScope(Dispatchers.Main).launch {
                    val startTime = System.currentTimeMillis()
                    while (isActive) {
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                        // Logarithmic curve: rises quickly then slows, caps at 0.9
                        val estimated = min(0.9f, ln(1f + elapsed) / ln(1f + 30f))
                        _modelLoadingProgress.postValue(estimated)
                        _loadedModelStatus.postValue("${round(100 * estimated).toInt()}%")
                        delay(100)
                    }
                }

                val llamaModel = llamaCpp.loadModel(
                    fileHandle.path,
                    object: LlamaProgressCallback {
                        override fun onProgress(progress: Float) {
                            // Real progress from llama.cpp (0→1 during load_all_data)
                        }
                    }
                )
                progressJob.cancel()

                // Load mmproj for vision models
                // clip.cpp uses std::ifstream which needs a real filesystem path,
                // so we copy the mmproj to a temp file in app-private storage.
                if (modelInfo.mmprojFilename != null) {
                    _loadedModelStatus.postValue("Loading vision...")
                    val mmprojTempFile = java.io.File(app.cacheDir, "mmproj_temp.gguf")
                    val copied = storageRepository.copyModelToFile(modelInfo.mmprojFilename, mmprojTempFile)
                    if (copied) {
                        android.util.Log.d("ConversationVM", "Loading mmproj from ${mmprojTempFile.absolutePath}")
                        llamaModel.loadMmprojModel(mmprojTempFile.absolutePath)
                        mmprojTempFile.delete()
                        android.util.Log.d("ConversationVM", "mmproj loaded, supportsVision=${llamaModel.supportsVision()}")
                    } else {
                        android.util.Log.d("ConversationVM", "mmproj file not found: ${modelInfo.mmprojFilename}")
                    }
                }

                val modelSize = llamaModel.getModelSize()
                val modelDescription = Formatter.formatFileSize(app, modelSize)
                val nCtxTrain = llamaModel.getContextTrainSize()
                _maxContextSize.postValue(minOf(nCtxTrain, 16384))
                // Load saved per-model params, or use defaults
                val savedMap = storagePreferences.getModelGenerationParams(modelInfo.filename)
                val params = if (savedMap != null) {
                    GenerationParams.fromMap(savedMap)
                } else {
                    GenerationParams()
                }
                _generationParams.postValue(params)
                val llamaSession = createSessionWithParams(llamaModel, params)
                if (llamaSession == null) {
                    _loadedModelStatus.postValue("Failed to create session")
                    llamaModel.unloadModel()
                    return@withContext
                }
                this@ConversationViewModel.llamaModel = llamaModel
                this@ConversationViewModel.llamaSession = llamaSession
                _supportsThinking.postValue(llamaModel.supportsThinking())
                _supportsVision.postValue(llamaModel.supportsVision())
                _modelLoadingProgress.postValue(0f)
                _loadedModelStatus.postValue(modelDescription)
                _isModelReady.postValue(true)
                _sessionModelHint.postValue(null)

                // If there are existing messages, replay history into the new session
                val messages = uiState.messages.toList()
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

    private fun createSessionWithParams(model: LlamaModel, params: GenerationParams): LlamaGenerationSession? {
        return model.createSession(
            params.contextSize,
            params.temperature,
            params.topP,
            params.repetitionPenalty,
            params.topK,
            params.minP,
            params.seed,
            params.thinkingBudget
        )
    }

    /**
     * Copy the picked image to app cache so it persists after the picker URI expires.
     * Returns a content URI via FileProvider so external apps can also access it.
     */
    private fun cacheImage(sourceUri: Uri): Uri? {
        return try {
            val cacheDir = java.io.File(app.cacheDir, "chat_images")
            cacheDir.mkdirs()
            val destFile = java.io.File(cacheDir, "img_${System.currentTimeMillis()}.jpg")
            app.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.fileprovider", destFile
            )
        } catch (e: Exception) {
            null
        }
    }

    @MainThread
    fun updateGenerationParams(params: GenerationParams) {
        val oldParams = _generationParams.value ?: GenerationParams()
        _generationParams.value = params

        // Save as per-model defaults
        val modelFilename = _loadedModel.value?.filename
        if (modelFilename != null) {
            storagePreferences.setModelGenerationParams(modelFilename, params.toMap())
        }

        // Persist to Room if we have an active session
        val sessionId = _currentSessionId.value
        if (sessionId != null) {
            viewModelScope.launch {
                chatRepository?.updateSessionParams(
                    sessionId,
                    params.contextSize, params.temperature, params.topP,
                    params.repetitionPenalty, params.topK, params.minP, params.seed,
                    params.thinkingBudget
                )
            }
        }

        // If context size changed, must recreate session (resets conversation)
        if (oldParams.contextSize != params.contextSize) {
            val model = llamaModel ?: return
            viewModelScope.launch {
                generatingJob?.cancel()
                generatingJob?.join()
                generatingJob = null

                val prevSession = llamaSession
                llamaSession = null

                _currentSessionId.value = null
                uiState.resetMessages()

                withContext(Dispatchers.Default) {
                    prevSession?.destroy()
                    val newSession = createSessionWithParams(model, params) ?: return@withContext
                    this@ConversationViewModel.llamaSession = newSession
                }
            }
        } else {
            // Other params: recreate session but replay history
            val model = llamaModel ?: return
            viewModelScope.launch {
                generatingJob?.cancel()
                generatingJob?.join()
                generatingJob = null

                val prevSession = llamaSession
                llamaSession = null

                withContext(Dispatchers.Default) {
                    prevSession?.destroy()
                    val newSession = createSessionWithParams(model, params) ?: return@withContext
                    this@ConversationViewModel.llamaSession = newSession

                    val messages = uiState.messages.toList()
                    if (messages.isNotEmpty()) {
                        replayHistoryToSession(newSession, messages)
                    }
                }
            }
        }
    }

    @MainThread
    fun addMessage(message: Message, imageUri: Uri? = null) {
        // Cache the image so it persists beyond the picker URI lifetime
        val cachedImageUri = imageUri?.let { cacheImage(it) }
        val userMessage = if (cachedImageUri != null) message.copy(imageUri = cachedImageUri) else message

        val enableThinking = _thinkingEnabled.value == true
        Snapshot.withMutableSnapshot {
            uiState.addMessage(userMessage)
            val now = System.currentTimeMillis()
            uiState.addMessage(
                Message(
                    "Assistant",
                    "",
                    thinkingStartTimeMs = if (enableThinking) now else 0L,
                    responseStartTimeMs = now
                )
            )
        }

        _isGenerating.postValue(true)
        generatingJob = viewModelScope.launch {
            // Persist user message
            val sessionId = ensureSession(message)
            persistMessage(sessionId, message)

            withContext(Dispatchers.Default) {
                val llamaSession = llamaSession ?: return@withContext

                // Send image data to native layer before addMessage
                if (imageUri != null) {
                    try {
                        val imageBytes = withContext(Dispatchers.IO) {
                            resizeImageForVision(imageUri)
                        }
                        if (imageBytes != null) {
                            android.util.Log.d("ConversationVM", "Image loaded: ${imageBytes.size} bytes")
                            llamaSession.setImageData(imageBytes)
                        } else {
                            android.util.Log.e("ConversationVM", "Failed to read image from $imageUri")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ConversationVM", "Error reading image: ${e.message}")
                    }
                }

                llamaSession.addMessage(message.content, enableThinking)

                val callback = object: LlamaGenerationCallback {
                    var totalTokens = 0
                    var thinkingTokenCount = 0
                    var thinkingComplete = !enableThinking
                    var modelIsThinking = enableThinking
                    override fun onFullResponse(response: String) {
                        totalTokens++
                        var string = ResponseProcessor.process(response)

                        // Detect thinking from model output even when the
                        // toggle is off (models like LFM 2.5 always think)
                        var thinkingJustStarted = false
                        if (!modelIsThinking && string.startsWith("<think>")) {
                            modelIsThinking = true
                            thinkingComplete = false
                            thinkingJustStarted = true
                        }

                        if (!thinkingComplete && string.contains("</think>")) {
                            thinkingComplete = true
                            thinkingTokenCount = totalTokens
                        }
                        val currentThinkingTokens = if (thinkingComplete) thinkingTokenCount else totalTokens
                        val finalString = string
                        Snapshot.withMutableSnapshot {
                            if (thinkingJustStarted) {
                                uiState.markThinkingStarted()
                            }
                            uiState.updateLastMessage(
                                finalString,
                                thinkingTokens = currentThinkingTokens,
                                responseTokens = totalTokens - currentThinkingTokens
                            )
                        }
                    }
                }
                while (this.isActive && llamaSession.generate(callback) == 0) {
                    // wait for the response
                }
                llamaSession.printReport()
                Snapshot.withMutableSnapshot {
                    uiState.finalizeLastMessage()
                }
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
        val params = _generationParams.value ?: GenerationParams()
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
                updatedAt = now,
                contextSize = params.contextSize,
                temperature = params.temperature,
                topP = params.topP,
                repetitionPenalty = params.repetitionPenalty,
                topK = params.topK,
                minP = params.minP,
                seed = params.seed,
                thinkingBudget = params.thinkingBudget
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
                responseDurationSeconds = message.responseDurationSeconds,
                timestamp = message.timestamp
            )
        )
    }

    @MainThread
    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val messages = chatRepository?.getMessages(sessionId) ?: return@launch
            val sessionEntity = chatRepository.getSession(sessionId)
            val uiMessages = messages.map { entity ->
                Message(
                    author = entity.author,
                    content = entity.content,
                    thinkingDurationSeconds = entity.thinkingDurationSeconds,
                    thinkingTokens = entity.thinkingTokens,
                    responseTokens = entity.responseTokens,
                    responseDurationSeconds = entity.responseDurationSeconds,
                    timestamp = entity.timestamp
                )
            }
            _currentSessionId.value = sessionId

            // Restore generation params from session
            if (sessionEntity != null) {
                val params = GenerationParams(
                    contextSize = sessionEntity.contextSize,
                    temperature = sessionEntity.temperature,
                    topP = sessionEntity.topP,
                    repetitionPenalty = sessionEntity.repetitionPenalty,
                    topK = sessionEntity.topK,
                    minP = sessionEntity.minP,
                    seed = sessionEntity.seed,
                    thinkingBudget = sessionEntity.thinkingBudget
                )
                _generationParams.value = params
            }

            // Show model hint if session used a different model
            if (sessionEntity != null &&
                sessionEntity.modelFilename.isNotEmpty() &&
                sessionEntity.modelFilename != _loadedModel.value?.filename
            ) {
                _sessionModelHint.value = Pair(sessionEntity.modelName, sessionEntity.modelFilename)
            } else {
                _sessionModelHint.value = null
            }

            uiState.setMessages(uiMessages)

            // Recreate native session with restored params and replay history
            val model = llamaModel
            if (model != null) {
                val prevSession = llamaSession
                llamaSession = null
                withContext(Dispatchers.Default) {
                    prevSession?.destroy()
                    val params = _generationParams.value ?: GenerationParams()
                    val newSession = createSessionWithParams(model, params) ?: return@withContext
                    this@ConversationViewModel.llamaSession = newSession
                    replayHistoryToSession(newSession, uiMessages)
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
            _sessionModelHint.value = null
            uiState.resetMessages()

            // Recreate native session with clean KV cache
            val model = llamaModel
            if (model != null) {
                val prevSession = llamaSession
                llamaSession = null
                withContext(Dispatchers.Default) {
                    prevSession?.destroy()
                    val params = _generationParams.value ?: GenerationParams()
                    val newSession = createSessionWithParams(model, params) ?: return@withContext
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

    fun dismissSessionModelHint() {
        _sessionModelHint.value = null
    }

    @MainThread
    fun loadModelByFilename(filename: String) {
        _sessionModelHint.value = null
        val modelInfo = ModelInfoProvider.getByFilename(filename)
            ?: ModelInfoProvider.createCustomModelInfo(filename, filename.removeSuffix(".gguf"), 0)
        loadModel(modelInfo)
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
                _supportsVision.postValue(false)
            }
        }
    }

    private fun resizeImageForVision(uri: Uri): ByteArray? {
        val inputStream = app.contentResolver.openInputStream(uri) ?: return null

        // Decode bounds first
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = app.contentResolver.openInputStream(uri) ?: return null
        BitmapFactory.decodeStream(boundsStream, null, options)
        boundsStream.close()

        val maxDimension = 768
        val origWidth = options.outWidth
        val origHeight = options.outHeight
        val scaleFactor = if (max(origWidth, origHeight) > maxDimension) {
            maxDimension.toFloat() / max(origWidth, origHeight)
        } else {
            1f
        }

        // Subsample for memory efficiency
        options.inJustDecodeBounds = false
        options.inSampleSize = (1f / scaleFactor).toInt().coerceAtLeast(1)

        val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()
        if (bitmap == null) return null

        // Scale to exact target if needed
        val targetW = (origWidth * scaleFactor).toInt().coerceAtLeast(1)
        val targetH = (origHeight * scaleFactor).toInt().coerceAtLeast(1)
        val scaled = if (bitmap.width != targetW || bitmap.height != targetH) {
            val s = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            bitmap.recycle()
            s
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        scaled.recycle()
        return out.toByteArray()
    }

    fun resetModelList() {
        _models.postValue(emptyList())
    }

}
