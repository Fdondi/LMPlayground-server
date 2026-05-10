package com.druk.lmplayground.conversation

import android.app.Application
import android.text.format.Formatter
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.druk.llamacpp.InferenceLimits
import com.druk.llamacpp.InferenceState
import com.druk.llamacpp.InferenceUnavailableException
import com.druk.llamacpp.LlamaCpp
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaGenerationSession
import com.druk.llamacpp.LlamaModel
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.PayloadTooLargeException
import com.druk.lmplayground.App
import com.druk.lmplayground.data.ChatMessageEntity
import com.druk.lmplayground.data.ChatRepository
import com.druk.lmplayground.data.ChatSessionEntity
import com.druk.lmplayground.data.SystemPromptEntity
import com.druk.lmplayground.data.SystemPromptRepository
import com.druk.lmplayground.models.DeviceCapability
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import com.druk.lmplayground.tools.ToolRegistry
import org.json.JSONArray
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.ln
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
    private val _supportsToolCalling = MutableLiveData(false)
    private val _toolEnabledStates = MutableLiveData<Map<String, Boolean>>(emptyMap())
    val toolRegistry = ToolRegistry.createDefault(app)
    val toolEnabledStates: LiveData<Map<String, Boolean>> = _toolEnabledStates
    private val _systemPrompt = MutableLiveData("")
    private val _systemPromptId = MutableLiveData<String?>(null)
    /**
     * One-shot user-facing error messages (e.g. "message too long").
     * The UI shows a Toast and resets to null via [consumeUserError].
     */
    private val _userError = MutableLiveData<String?>(null)
    /**
     * Set when [loadModel] hits the RAM-fit gate. The UI surfaces a
     * confirmation dialog so the user can override and load anyway.
     * Carries the (modelInfo, neededRam, totalRam) tuple so the dialog
     * can show concrete numbers without re-querying.
     */
    private val _pendingRamWarning =
        MutableLiveData<RamWarning?>(null)

    /**
     * Set when the native loader returns null — the GGUF is corrupt,
     * unreadable, or uses an architecture this build of llama.cpp
     * doesn't recognize. The UI surfaces a one-shot AlertDialog and
     * resets to null via [consumeModelLoadError].
     */
    private val _modelLoadError = MutableLiveData<String?>(null)

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
    val supportsToolCalling: LiveData<Boolean> = _supportsToolCalling
    val systemPrompt: LiveData<String> = _systemPrompt
    val systemPromptId: LiveData<String?> = _systemPromptId
    val userError: LiveData<String?> = _userError
    val pendingRamWarning: LiveData<RamWarning?> = _pendingRamWarning
    val modelLoadError: LiveData<String?> = _modelLoadError

    /** Called by the UI after surfacing the error (e.g. as a Toast). */
    @MainThread
    fun consumeUserError() { _userError.value = null }

    @MainThread
    fun consumeModelLoadError() { _modelLoadError.value = null }

    @MainThread
    fun dismissRamWarning() { _pendingRamWarning.value = null }

    @MainThread
    fun confirmLoadDespiteRamWarning() {
        val pending = _pendingRamWarning.value ?: return
        _pendingRamWarning.value = null
        loadModel(pending.modelInfo, forceLoad = true)
    }

    val uiState = ConversationUiState(
        initialMessages = emptyList()
    )

    // Session persistence
    private val chatRepository: ChatRepository? = (app as? App)?.chatRepository
    private val systemPromptRepository: SystemPromptRepository? =
        (app as? App)?.systemPromptRepository
    private val _currentSessionId = MutableLiveData<String?>(null)
    val currentSessionId: LiveData<String?> = _currentSessionId
    val sessions: LiveData<List<ChatSessionEntity>> =
        chatRepository?.getAllSessions() ?: MutableLiveData(emptyList())
    /**
     * Per-model MRU list. When the loaded model changes, switchMap swaps in
     * the corresponding query so the picker reflects "prompts I've used on
     * *this* model" with the most-recently-used one first.
     */
    val recentSystemPrompts: LiveData<List<SystemPromptEntity>> =
        _loadedModel.switchMap { model ->
            val repo = systemPromptRepository
            val filename = model?.filename
            if (repo == null || filename.isNullOrEmpty()) {
                MutableLiveData(emptyList())
            } else {
                repo.getRecentForModelLive(filename)
            }
        }

    init {
        // Surface :llama process death to the UI. When the inference engine
        // crashes, the app process keeps running — we just need to tear
        // down stale handles, mark the in-flight assistant message as
        // interrupted, and let the user reload the model.
        val client = (app as? App)?.inferenceClient
        if (client != null) {
            viewModelScope.launch {
                client.state.collect { s ->
                    if (s is InferenceState.Crashed) onInferenceCrashed()
                }
            }
        }
    }

    private fun onInferenceCrashed() {
        // Disable Send IMMEDIATELY (synchronously) so a tap that lands
        // between the crash and the recovery flow can't enqueue a new
        // generation through the stale UI state. setValue is safe here —
        // we're already on the main dispatcher (state.collect runs
        // inside viewModelScope.launch which uses Dispatchers.Main).
        _isModelReady.value = false
        _isGenerating.value = false

        // Snapshot the references that were live AT THE TIME OF THE
        // CRASH. We need these because our cleanup runs *after* a
        // potentially long wait — during which the user may have
        // acknowledged the crash and successfully loaded a NEW model.
        // We must only clear handles that still point at the dead
        // session/model; otherwise we'd close the new model's PFD and
        // null the new session, leaving the UI ready with no engine.
        val staleSession = llamaSession
        val staleModel = llamaModel
        val staleHandle = modelFileHandle

        viewModelScope.launch {
            // The cancelled generation coroutine isn't done yet —
            // generateAll() suspends up to 30 s waiting for the dead
            // worker to drain. Until that finally block has run, the
            // job's NonCancellable cleanup could still mutate
            // uiState.messages.lastOrNull() and persist whatever it
            // sees. We MUST wait for it to drain before we touch any
            // shared state, or it'll persist a future placeholder
            // against the now-stale sessionId.
            val priorJob = generatingJob
            generatingJob = null
            try {
                priorJob?.cancelAndJoin()
            } catch (_: Throwable) { /* job is dead either way */ }

            // If the user already reloaded a model during the wait, the
            // current handles are NOT the stale ones — they belong to a
            // working session on a fresh :llama process. Bail without
            // touching anything; the new load already set
            // _isModelReady=true and a sensible status.
            if (llamaModel !== staleModel ||
                llamaSession !== staleSession ||
                modelFileHandle !== staleHandle) {
                return@launch
            }

            // Still pointing at the dead handles — clean them up.
            llamaSession = null
            llamaModel = null
            modelFileHandle?.close()
            modelFileHandle = null
            _loadedModelStatus.value = app.getString(
                com.druk.lmplayground.R.string.inference_engine_crashed,
            )
            Snapshot.withMutableSnapshot {
                // If the assistant was mid-response when the engine died,
                // append a clear marker so the user understands the
                // message stopped because of a crash, not because the
                // model finished.
                val last = uiState.messages.lastOrNull()
                if (last != null && last.author == "Assistant" && last.responseStartTimeMs > 0) {
                    val suffix = "\n\n_${app.getString(com.druk.lmplayground.R.string.inference_engine_crashed)}_"
                    uiState.updateLastMessage(
                        last.content + suffix,
                        thinkingTokens = last.thinkingTokens,
                        responseTokens = last.responseTokens,
                    )
                }
                uiState.finalizeLastMessage()
            }
        }
    }

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
    fun loadModel(modelInfo: ModelInfo, forceLoad: Boolean = false) {
        val llamaCpp = llamaCpp ?: return

        // RAM-fit warning. Run BEFORE we tear down the currently-loaded
        // model so the user can cancel the warning and keep their
        // existing session intact. The actual load below still skips
        // the check when forceLoad=true.
        if (!forceLoad) {
            val fileSizeBytes = storageRepository.getModelFiles()
                .find { it.name == modelInfo.filename }?.sizeBytes ?: 0L
            val totalRamBytes = DeviceCapability.totalRamBytes(app)
            if (DeviceCapability.exceedsRamBudget(fileSizeBytes, totalRamBytes)) {
                _pendingRamWarning.value = RamWarning(
                    modelInfo = modelInfo,
                    neededRam = Formatter.formatFileSize(app, fileSizeBytes),
                    totalRam = Formatter.formatFileSize(app, totalRamBytes),
                )
                return
            }
        }

        _models.postValue(emptyList())
        _isModelReady.postValue(false)

        viewModelScope.launch {
            // If we're recovering from a `:llama` crash, the InferenceClient
            // is in sticky `Crashed` state. Acknowledge it so the next AIDL
            // call uses the freshly auto-rebound service. Safe no-op when
            // the state is already Connected.
            (app as? App)?.inferenceClient?.let { ic ->
                if (ic.state.value is InferenceState.Crashed) {
                    ic.acknowledgeCrash()
                    // The auto-rebound service may still be landing — wait
                    // up to 5s for the next Connected transition before
                    // proceeding with loadModel.
                    try {
                        kotlinx.coroutines.withTimeout(5_000) {
                            ic.awaitConnected()
                        }
                    } catch (_: Throwable) {
                        _loadedModelStatus.postValue(
                            app.getString(com.druk.lmplayground.R.string.inference_engine_crashed)
                        )
                        return@launch
                    }
                }
            }

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

                // llama.cpp only reports progress during tensor pointer setup,
                // which is near-instant with mmap. The slow parts (GGUF metadata
                // parsing, mmap init, buffer allocation) report nothing.
                // Animate estimated progress as a fallback so the bar moves
                // during the silent phases; the real callback overrides as
                // soon as the first real value arrives.
                val realProgressSeen = java.util.concurrent.atomic.AtomicBoolean(false)
                val progressJob = CoroutineScope(Dispatchers.Main).launch {
                    val startTime = System.currentTimeMillis()
                    while (isActive) {
                        if (!realProgressSeen.get()) {
                            val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                            // Logarithmic curve: rises quickly then slows, caps at 0.9
                            val estimated = min(0.9f, ln(1f + elapsed) / ln(1f + 30f))
                            _modelLoadingProgress.postValue(estimated)
                            _loadedModelStatus.postValue("${round(100 * estimated).toInt()}%")
                        }
                        delay(100)
                    }
                }

                // Wrap the entire load + session setup so that the
                // progress-animation job is cancelled on every exit
                // path (success, exception, coroutine cancel). Without
                // this, a binder failure during loadModel would leave
                // the progress job ticking forever, overwriting the
                // crash status with bogus "85%" updates.
                try {
                    // Send the PFD across the binder. The service dups the
                    // FD into its own process and builds a process-local
                    // fd:N string. The app keeps `fileHandle` (the original
                    // PFD) alive via `modelFileHandle` for the model's
                    // lifetime.
                    val llamaModel = llamaCpp.loadModel(
                        fileHandle.pfd,
                        object: LlamaProgressCallback {
                            override fun onProgress(progress: Float) {
                                realProgressSeen.set(true)
                                _modelLoadingProgress.postValue(progress)
                                _loadedModelStatus.postValue(
                                    "${round(100 * progress).toInt()}%"
                                )
                            }
                        }
                    )
                    val modelSize = llamaModel.getModelSize()
                    val modelDescription = Formatter.formatFileSize(app, modelSize)
                    // Surface "Model is loaded" + "<name> - <size>" in the
                    // FGS notification (otherwise hidden under MIN importance,
                    // but visible when the user expands the Silent group in
                    // the shade).
                    llamaCpp.setForegroundContent(
                        app.getString(com.druk.lmplayground.R.string.inference_notification_loaded_title),
                        "${modelInfo.name} - $modelDescription",
                    )
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
                    // Every model load starts without a system prompt. Per-model
                    // MRU is surfaced in the picker row so the user can one-tap
                    // re-apply their most-recent prompt for this model.
                    _systemPrompt.postValue("")
                    _systemPromptId.postValue(null)
                    val llamaSession = createSessionWithParams(llamaModel, params, "")
                    if (llamaSession == null) {
                        _loadedModelStatus.postValue("Failed to create session")
                        llamaModel.unloadModel()
                        return@withContext
                    }
                    this@ConversationViewModel.llamaModel = llamaModel
                    this@ConversationViewModel.llamaSession = llamaSession
                    _supportsThinking.postValue(llamaModel.supportsThinking())
                    val toolCallingSupported = llamaModel.supportsToolCalling()
                    _supportsToolCalling.postValue(toolCallingSupported)
                    if (toolCallingSupported) {
                        val states = mutableMapOf<String, Boolean>()
                        for (tool in toolRegistry.getAllTools()) {
                            val enabled = storagePreferences.isToolEnabled(tool.name)
                            toolRegistry.setToolEnabled(tool.name, enabled)
                            states[tool.name] = enabled
                        }
                        _toolEnabledStates.postValue(states)
                    } else {
                        _toolEnabledStates.postValue(emptyMap())
                    }
                    _modelLoadingProgress.postValue(0f)
                    _loadedModelStatus.postValue(modelDescription)
                    _sessionModelHint.postValue(null)

                    // Replay history into the new session BEFORE marking the
                    // model ready. If a persisted message exceeds the
                    // 700 KB binder ceiling, replayHistory throws — and we
                    // do NOT want the user to start a new turn against a
                    // session that's missing prior context (the model
                    // would answer follow-up questions as if they were
                    // fresh prompts). Tear the session+model down on
                    // failure and surface a clear error.
                    val messages = uiState.messages.toList()
                    if (messages.isNotEmpty()) {
                        try {
                            replayHistoryToSession(llamaSession, messages)
                        } catch (e: PayloadTooLargeException) {
                            this@ConversationViewModel.llamaSession = null
                            this@ConversationViewModel.llamaModel = null
                            try { llamaSession.destroy() } catch (_: Throwable) {}
                            try { llamaModel.unloadModel() } catch (_: Throwable) {}
                            _loadedModelStatus.postValue(
                                app.getString(
                                    com.druk.lmplayground.R.string.replay_history_too_large
                                )
                            )
                            return@withContext
                        }
                    }
                    _isModelReady.postValue(true)

                    // Update session model info if we have an active session
                    val sessionId = _currentSessionId.value
                    if (sessionId != null) {
                        chatRepository?.updateSessionModel(
                            sessionId, modelInfo.filename, modelInfo.name
                        )
                    }
                } catch (t: Throwable) {
                    // Surface the failure to the user instead of leaving
                    // the picker stuck on "Loading…" forever.
                    _modelLoadingProgress.postValue(0f)
                    val statusMsg = app.getString(
                        com.druk.lmplayground.R.string.model_load_failed_status
                    )
                    _loadedModelStatus.postValue(statusMsg)
                    fileHandle.close()
                    if (t !is kotlinx.coroutines.CancellationException) {
                        android.util.Log.w("ConversationViewModel", "loadModel failed", t)
                        _modelLoadError.postValue(
                            app.getString(
                                com.druk.lmplayground.R.string.model_load_failed_message,
                                modelInfo.name,
                            )
                        )
                    } else {
                        throw t
                    }
                } finally {
                    progressJob.cancel()
                }
            }
        }
    }

    /**
     * Pre-flight check for every session-recreation path. Verifies the
     * system prompt and each persisted message fit under the AIDL
     * binder cap so the recreate doesn't half-succeed: destroying the
     * old session and then throwing inside `createSession` /
     * `replayHistory` leaves the UI with `_isModelReady=true` but
     * `llamaSession=null`, and the next Send breaks.
     *
     * Returns `null` if all payloads are within budget. Returns a
     * user-facing localized error string (already posted to
     * `_userError`) if anything is too large; the caller MUST then
     * abort without mutating session state.
     */
    private fun validateReplaySize(systemPrompt: String, messages: List<Message>): Boolean {
        val promptBytes = systemPrompt.length * 2
        if (promptBytes > InferenceLimits.MAX_PAYLOAD_BYTES) {
            _userError.postValue(
                app.getString(
                    com.druk.lmplayground.R.string.system_prompt_too_large,
                    promptBytes / 1024,
                    InferenceLimits.MAX_PAYLOAD_BYTES / 1024,
                )
            )
            return false
        }
        for (msg in messages) {
            if (msg.content.length * 2 > InferenceLimits.MAX_PAYLOAD_BYTES) {
                _userError.postValue(
                    app.getString(com.druk.lmplayground.R.string.history_message_too_large)
                )
                return false
            }
        }
        return true
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

    private fun createSessionWithParams(
        model: LlamaModel,
        params: GenerationParams,
        systemPrompt: String = _systemPrompt.value.orEmpty()
    ): LlamaGenerationSession? {
        return try {
            model.createSession(
                params.contextSize,
                params.temperature,
                params.topP,
                params.repetitionPenalty,
                params.topK,
                params.minP,
                params.seed,
                params.thinkingBudget,
                systemPrompt
            )
        } catch (e: InferenceUnavailableException) {
            // The :llama service died (or hasn't bound yet). Surface a
            // recoverable error to the UI rather than letting the AIDL
            // exception propagate and crash the app process.
            android.util.Log.w("ConversationViewModel", "createSession failed: service unavailable", e)
            _userError.postValue(
                app.getString(com.druk.lmplayground.R.string.inference_engine_unavailable)
            )
            null
        }
    }

    @MainThread
    fun updateGenerationParams(params: GenerationParams) {
        val oldParams = _generationParams.value ?: GenerationParams()
        val systemPrompt = _systemPrompt.value.orEmpty()

        // Pre-validate BEFORE mutating any state. Without this, an
        // oversized prompt or saved message would set _generationParams
        // (UI shows the new params) and persist the update to Room,
        // then fail to recreate the session — leaving the UI showing
        // the new params but the engine running on the old session.
        val messagesToReplay = if (oldParams.contextSize != params.contextSize) {
            // Context-size change resets the conversation, no replay.
            emptyList()
        } else {
            uiState.messages.toList()
        }
        if (llamaModel != null && !validateReplaySize(systemPrompt, messagesToReplay)) return

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

                _currentSessionId.value = null
                uiState.resetMessages()

                withContext(Dispatchers.Default) {
                    val newSession = createSessionWithParams(model, params, systemPrompt)
                    if (newSession != null) {
                        this@ConversationViewModel.llamaSession = newSession
                        prevSession?.destroy()
                    } else {
                        // Keep using the old session if we couldn't make a new one.
                        prevSession?.destroy()
                        this@ConversationViewModel.llamaSession = null
                    }
                }
            }
        } else {
            // Other params: recreate session but replay history. We
            // already pre-validated at the top, so no validation here.
            val model = llamaModel ?: return
            val messages = uiState.messages.toList()
            viewModelScope.launch {
                generatingJob?.cancel()
                generatingJob?.join()
                generatingJob = null

                val prevSession = llamaSession

                withContext(Dispatchers.Default) {
                    // Create the new session FIRST. Only after a successful
                    // create + replay do we destroy the old one — this way
                    // a late failure leaves the prior session intact and
                    // usable instead of stranding the UI session-less.
                    val newSession = createSessionWithParams(model, params, systemPrompt)
                        ?: return@withContext
                    try {
                        if (messages.isNotEmpty()) {
                            replayHistoryToSession(newSession, messages)
                        }
                    } catch (e: PayloadTooLargeException) {
                        newSession.destroy()
                        _userError.postValue(
                            app.getString(com.druk.lmplayground.R.string.history_message_too_large)
                        )
                        return@withContext
                    }
                    this@ConversationViewModel.llamaSession = newSession
                    prevSession?.destroy()
                }
            }
        }
    }

    @MainThread
    fun addMessage(message: Message) {
        val enableThinking = _thinkingEnabled.value == true

        // Pre-validate the message size BEFORE we mutate any UI state.
        // If we appended the user/assistant placeholder first, an
        // oversized message would throw later — leaving the chat stuck
        // with `_isGenerating=true` and a half-empty assistant bubble.
        // A clean abort here matches what the user expects: nothing
        // visibly happened, but the input shows an error.
        val sizeBytes = message.content.length * 2
        if (sizeBytes > InferenceLimits.MAX_PAYLOAD_BYTES) {
            _userError.postValue(
                app.getString(
                    com.druk.lmplayground.R.string.message_too_large,
                    sizeBytes / 1024,
                    InferenceLimits.MAX_PAYLOAD_BYTES / 1024,
                )
            )
            return
        }

        Snapshot.withMutableSnapshot {
            uiState.addMessage(message)
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
        // Marker on the assistant placeholder we just added — used by the
        // cleanup path below to confirm the still-active message in
        // uiState is OURS and not a placeholder for some later turn the
        // user added after a crash + reload. Must be a field that
        // *every* phase of the placeholder preserves: previously this
        // was responseStartTimeMs, but addToolCallsToLastMessage resets
        // it to start the post-tool timer, so after the first tool call
        // the cleanup would always bail out and leave _isGenerating
        // stuck at true. Message.id is auto-incremented and never
        // changes through .copy() updates — the right identity field.
        val ourMessageId = uiState.messages.lastOrNull()?.id ?: -1L
        generatingJob = viewModelScope.launch {
            val ourJob = coroutineContext[Job]

            // Persist user message
            val sessionId = ensureSession(message)
            persistMessage(sessionId, message)

            withContext(Dispatchers.Default) {
                val llamaSession = llamaSession ?: return@withContext

                // Tools are active when model supports it and user has tools enabled
                val toolsActive = _supportsToolCalling.value == true
                    && toolRegistry.hasEnabledTools()
                try {
                    val toolsJson = if (toolsActive) toolRegistry.toOpenAIToolsJson() else "[]"
                    llamaSession.setTools(toolsJson)
                    // Persistent preamble KV cache: must be set after setTools
                    // (the fingerprint covers the active tool set) and before
                    // addMessage (the lazy load/save runs on the first
                    // addMessage of the session). It's a no-op if the model
                    // info is unavailable. Pruning the cache directory is
                    // best-effort; failures don't block generation.
                    applyPreambleCache(llamaSession, toolsJson)
                    llamaSession.addMessage(message.content, enableThinking)
                } catch (e: InferenceUnavailableException) {
                    android.util.Log.w("ConversationViewModel", "addMessage failed: service unavailable", e)
                    _userError.postValue(
                        app.getString(com.druk.lmplayground.R.string.inference_engine_unavailable)
                    )
                    Snapshot.withMutableSnapshot {
                        // Drop the empty assistant placeholder so the chat
                        // doesn't sit forever on a half-blank bubble.
                        if ((uiState.messages.lastOrNull() as? Message)?.id == ourMessageId) {
                            uiState.removeLastMessage()
                        }
                    }
                    _isGenerating.postValue(false)
                    return@withContext
                }

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
                // Drive the generation loop, draining tool calls between
                // rounds. `generateAll()` is a single AIDL call that runs
                // service-side until the worker stops; it returns 2 when
                // the model emitted tool calls, 0 on natural stop, or
                // non-zero on error / cancel.
                //
                // Cancellation flows through the coroutine: cancelling
                // generatingJob cancels the suspend inside generateAll(),
                // which calls service.cancelGeneration() under the hood
                // and re-throws CancellationException once the worker
                // exits. We never re-enter the loop after a cancel.
                try {
                    var toolRounds = 0
                    val maxToolRounds = 5
                    while (true) {
                        val rc = llamaSession.generateAll(callback)
                        if (rc != 2 || toolRounds >= maxToolRounds || !this.isActive) {
                            break
                        }
                        toolRounds++

                        val toolCallsJson = llamaSession.getToolCallsJson()
                        android.util.Log.d(
                            "ConversationVM",
                            "Tool calls (round $toolRounds): $toolCallsJson",
                        )

                        val toolStartTime = System.currentTimeMillis()
                        val toolResults = toolRegistry.executeToolCalls(toolCallsJson)
                        val toolDurationMs = System.currentTimeMillis() - toolStartTime
                        android.util.Log.d(
                            "ConversationVM",
                            "Tool results (${toolDurationMs}ms): $toolResults",
                        )

                        val toolCallInfoList = buildToolCallInfoList(
                            toolCallsJson, toolResults, toolDurationMs,
                        )
                        Snapshot.withMutableSnapshot {
                            uiState.addToolCallsToLastMessage(toolCallInfoList)
                        }

                        llamaSession.submitToolResults(toolResults, enableThinking)

                        // Reset the streaming callback's per-round counters
                        // so the next generateAll() reports a fresh
                        // thinking-vs-response token split.
                        callback.totalTokens = 0
                        callback.thinkingTokenCount = 0
                        callback.thinkingComplete = !enableThinking
                        callback.modelIsThinking = enableThinking
                    }
                } catch (e: InferenceUnavailableException) {
                    android.util.Log.w("ConversationViewModel", "generateAll failed: service unavailable", e)
                    _userError.postValue(
                        app.getString(com.druk.lmplayground.R.string.inference_engine_unavailable)
                    )
                } finally {
                    // Cleanup must complete even if the coroutine was
                    // cancelled (Stop tapped). NonCancellable lets us
                    // finish the Room writes and UI tear-down without
                    // re-throwing CancellationException mid-cleanup.
                    withContext(kotlinx.coroutines.NonCancellable) {
                        try { llamaSession.printReport() } catch (_: Throwable) {}

                        // If a newer generation has taken over this slot
                        // (crash + reload + new prompt while we were
                        // draining the dead worker), our cleanup must NOT
                        // touch any UI/persistence — uiState.messages
                        // now belongs to the new turn, finalizing it
                        // would clobber the in-flight new generation.
                        // The new job's own finally will handle its
                        // state. We just exit quietly.
                        val supersededByNewer = generatingJob !== ourJob
                        // Belt-and-suspenders: also confirm the last
                        // message in uiState is still our placeholder
                        // by stable Message.id identity.
                        val last = uiState.messages.lastOrNull()
                        val stillOurMessage = last != null &&
                            last.author == "Assistant" &&
                            last.id == ourMessageId
                        if (supersededByNewer || !stillOurMessage) {
                            return@withContext
                        }

                        Snapshot.withMutableSnapshot {
                            uiState.finalizeLastMessage()
                        }
                        _isGenerating.postValue(false)

                        // Persist whatever the assistant produced — including
                        // a partially-streamed response on cancel — so
                        // reload-from-DB matches what the user saw on screen.
                        val assistantMessage = uiState.messages.lastOrNull()
                        if (assistantMessage != null && assistantMessage.author == "Assistant") {
                            try {
                                persistMessage(sessionId, assistantMessage)
                                chatRepository?.updateSessionTimestamp(
                                    sessionId,
                                    System.currentTimeMillis(),
                                )
                            } catch (_: Throwable) { /* best-effort */ }
                        }
                    }
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
                thinkingBudget = params.thinkingBudget,
                systemPrompt = _systemPrompt.value.orEmpty()
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

            // Pre-flight the saved chat against the AIDL payload cap
            // BEFORE switching any UI state. If a persisted message
            // (or the session's saved system prompt) is too large for
            // the binder, refuse the swap entirely — keep the user on
            // their current chat instead of half-loading a session
            // whose generated output would silently come from the
            // OLD session's KV cache.
            val newSystemPrompt = sessionEntity?.systemPrompt ?: ""
            if (!validateReplaySize(newSystemPrompt, uiMessages)) {
                return@launch
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
                _systemPrompt.value = sessionEntity.systemPrompt
                // Try to rehydrate the library id from the stored text so that
                // "Update prompt" in the Generation Params sheet can target the
                // same library entry when it still matches.
                val stored = sessionEntity.systemPrompt
                if (stored.isEmpty()) {
                    _systemPromptId.value = null
                } else {
                    val entity = systemPromptRepository?.findByText(stored)
                    _systemPromptId.value = entity?.id
                }
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
                val systemPrompt = _systemPrompt.value.orEmpty()
                // Pre-validation already happened at the top of this
                // function (before any UI state mutation). The
                // try/catch below is defense-in-depth in case the
                // saved system prompt diverges from sessionEntity's.
                val prevSession = llamaSession
                withContext(Dispatchers.Default) {
                    val params = _generationParams.value ?: GenerationParams()
                    val newSession = createSessionWithParams(model, params, systemPrompt)
                        ?: return@withContext
                    try {
                        replayHistoryToSession(newSession, uiMessages)
                    } catch (e: PayloadTooLargeException) {
                        newSession.destroy()
                        _userError.postValue(
                            app.getString(com.druk.lmplayground.R.string.history_message_too_large)
                        )
                        return@withContext
                    }
                    this@ConversationViewModel.llamaSession = newSession
                    prevSession?.destroy()
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

    /**
     * Apply a system prompt to the current session. Recreates the native session
     * so the new prompt takes effect, replays any existing messages, and bumps
     * the library entry's `lastUsedAt` when [promptId] is non-null.
     *
     * The intended caller is the picker row on an empty conversation, but the
     * method also supports mid-chat swaps (history replay handles it).
     */
    @MainThread
    fun applySystemPrompt(promptId: String?, text: String) {
        val current = _systemPrompt.value.orEmpty()
        val currentId = _systemPromptId.value
        if (current == text && currentId == promptId) return

        // Pre-validate BEFORE mutating any state. Without this, an
        // oversized prompt would set _systemPrompt (UI shows the new
        // prompt), destroy the old session, then throw inside
        // createSession — leaving the user with an in-flight UI but
        // a null llamaSession. The next Send would hit the early-
        // return inside addMessage and the placeholder would never
        // get cleaned up.
        val messages = uiState.messages.toList()
        if (!validateReplaySize(text, messages)) return

        _systemPrompt.value = text
        _systemPromptId.value = promptId

        // Persist on the active session row if one exists.
        val sessionId = _currentSessionId.value
        if (sessionId != null) {
            viewModelScope.launch {
                chatRepository?.updateSessionSystemPrompt(sessionId, text)
            }
        }

        // Bump per-model MRU for library-sourced picks.
        if (promptId != null) {
            val modelFilename = _loadedModel.value?.filename
            if (!modelFilename.isNullOrEmpty()) {
                viewModelScope.launch {
                    systemPromptRepository?.touchUsage(promptId, modelFilename)
                }
            }
        }

        // Recreate the native session so the prompt lands as message[0].
        val model = llamaModel ?: return
        val params = _generationParams.value ?: GenerationParams()
        viewModelScope.launch {
            generatingJob?.cancel()
            generatingJob?.join()
            generatingJob = null

            val prevSession = llamaSession

            withContext(Dispatchers.Default) {
                // Create-then-destroy: keep the old session alive as a
                // fallback if creation throws (defense-in-depth on top
                // of the validateReplaySize pre-check).
                val newSession = try {
                    createSessionWithParams(model, params, text)
                } catch (e: PayloadTooLargeException) {
                    _userError.postValue(
                        app.getString(
                            com.druk.lmplayground.R.string.system_prompt_too_large,
                            text.length * 2 / 1024,
                            InferenceLimits.MAX_PAYLOAD_BYTES / 1024,
                        )
                    )
                    null
                } ?: return@withContext
                try {
                    if (messages.isNotEmpty()) {
                        replayHistoryToSession(newSession, messages)
                    }
                } catch (e: PayloadTooLargeException) {
                    newSession.destroy()
                    _userError.postValue(
                        app.getString(com.druk.lmplayground.R.string.history_message_too_large)
                    )
                    return@withContext
                }
                this@ConversationViewModel.llamaSession = newSession
                prevSession?.destroy()
            }
        }
    }

    @MainThread
    fun clearSystemPrompt() = applySystemPrompt(null, "")

    /**
     * Overwrite the text of the library entry currently backing this session
     * (if any) and apply the new text to the session. Used by the
     * Generation Params "Update prompt" button.
     */
    @MainThread
    fun updateLinkedSystemPrompt(text: String) {
        val trimmed = text.trim()
        val id = _systemPromptId.value
        val repo = systemPromptRepository
        if (id == null || repo == null) {
            applySystemPrompt(null, trimmed)
            return
        }
        viewModelScope.launch {
            val existing = repo.getById(id) ?: return@launch
            repo.update(existing.copy(text = trimmed))
            applySystemPrompt(id, trimmed)
        }
    }

    /**
     * Persist a brand-new system prompt to the library and apply it to the
     * current session.
     */
    @MainThread
    fun createAndApplySystemPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val repo = systemPromptRepository ?: run {
            applySystemPrompt(null, trimmed)
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val entity = SystemPromptEntity(
                id = UUID.randomUUID().toString(),
                text = trimmed,
                createdAt = now,
                updatedAt = now
            )
            repo.insert(entity)
            applySystemPrompt(entity.id, entity.text)
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
            // Tear down native handles only when something is actually
            // loaded — but always clear the user-visible LiveData state
            // below. The failed-load case (e.g. RAM gate refused) leaves
            // _loadedModel + _loadedModelStatus set with null native
            // handles; without this, tapping Unload was a no-op for that
            // path.
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
            }

            _loadedModel.postValue(null)
            _loadedModelStatus.postValue(null)
            _isModelReady.postValue(false)
            _supportsThinking.postValue(false)
            _supportsToolCalling.postValue(false)
            _toolEnabledStates.postValue(emptyMap())
        }
    }

    @MainThread
    fun setToolEnabled(toolName: String, enabled: Boolean) {
        toolRegistry.setToolEnabled(toolName, enabled)
        storagePreferences.setToolEnabled(toolName, enabled)
        val states = _toolEnabledStates.value.orEmpty().toMutableMap()
        states[toolName] = enabled
        _toolEnabledStates.value = states
    }

    /**
     * Set up the persistent preamble (system prompt + tools) KV cache for
     * [session]. Path is `<filesDir>/kv_preamble/<fingerprint>` where
     * fingerprint is SHA-1 over (model filename, system prompt, tools
     * JSON). Cache files are shared across sessions: any new conversation
     * with the same model / sys prompt / tool set re-uses the same disk
     * cache. LRU prune keeps disk footprint bounded ([KV_PREAMBLE_KEEP]
     * most-recent files).
     */
    private fun applyPreambleCache(
        session: LlamaGenerationSession,
        toolsJson: String,
    ) {
        try {
            val modelInfo = _loadedModel.value
            val modelName = modelInfo?.filename
            if (modelName.isNullOrEmpty()) {
                // Model info isn't ready (shouldn't happen here but be safe).
                session.setPreambleCachePath("", "")
                return
            }
            // Include the loaded model's byte size in the fingerprint so a
            // replaced-but-same-named model file invalidates stale caches.
            // Filename alone wouldn't catch re-quantization or upgrades
            // where the filename was kept; the byte size differs in
            // virtually all real cases. getModelSize() is a cheap AIDL
            // call backed by an in-memory llama_model field.
            val modelSize = try { llamaModel?.getModelSize() ?: 0L } catch (_: Throwable) { 0L }
            val modelKey = "$modelName:$modelSize"
            val systemPrompt = _systemPrompt.value.orEmpty()
            val fingerprint = sha1Hex(
                "$modelKey $systemPrompt $toolsJson"
            )
            val dir = kvPreambleDir().apply { mkdirs() }
            val path = java.io.File(dir, fingerprint).absolutePath
            session.setPreambleCachePath(path, fingerprint)
            pruneOldKvPreambles(KV_PREAMBLE_KEEP)
        } catch (t: Throwable) {
            android.util.Log.w(
                "ConversationViewModel",
                "applyPreambleCache failed (continuing without cache)", t
            )
            try { session.setPreambleCachePath("", "") } catch (_: Throwable) {}
        }
    }

    private fun kvPreambleDir(): java.io.File =
        java.io.File(app.filesDir, "kv_preamble")

    private fun pruneOldKvPreambles(keep: Int) {
        try {
            val dir = kvPreambleDir()
            val bins = dir.listFiles()?.filter { it.name.endsWith(".bin") } ?: return
            if (bins.size <= keep) return
            val ordered = bins.sortedByDescending { it.lastModified() }
            for (i in keep until ordered.size) {
                val bin = ordered[i]
                bin.delete()
                java.io.File(bin.absolutePath.removeSuffix(".bin") + ".json").delete()
            }
        } catch (t: Throwable) {
            android.util.Log.w("ConversationViewModel", "pruneOldKvPreambles failed", t)
        }
    }

    private fun sha1Hex(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private fun buildToolCallInfoList(
        toolCallsJson: String,
        toolResultsJson: String,
        totalDurationMs: Long
    ): List<ToolCallInfo> {
        val calls = JSONArray(toolCallsJson)
        val results = JSONArray(toolResultsJson)
        val resultMap = mutableMapOf<String, String>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            resultMap[r.getString("id")] = r.getString("content")
        }
        val count = calls.length().coerceAtLeast(1)
        val perCallMs = totalDurationMs / count
        return (0 until calls.length()).map { i ->
            val call = calls.getJSONObject(i)
            val id = call.getString("id")
            ToolCallInfo(
                name = call.getString("name"),
                arguments = call.getString("arguments"),
                result = resultMap[id] ?: "",
                durationMs = perCallMs
            )
        }
    }

    fun resetModelList() {
        _models.postValue(emptyList())
    }

    data class RamWarning(
        val modelInfo: ModelInfo,
        val neededRam: String,
        val totalRam: String,
    )

    private companion object {
        // Number of preamble cache files to retain (LRU by mtime). Each
        // file is small relative to the model itself but scales with
        // (system_prompt + tools_description) token count — typically a
        // few KB to a few hundred KB. 8 covers "user has 8 different
        // model + tool-set combinations they use regularly" without
        // bloating /data.
        private const val KV_PREAMBLE_KEEP = 8

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
