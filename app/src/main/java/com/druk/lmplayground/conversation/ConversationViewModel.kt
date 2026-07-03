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
import com.druk.lmplayground.data.ChatSessionEntity
import com.druk.lmplayground.data.ConversationMetadata
import com.druk.lmplayground.data.SystemPromptEntity
import com.druk.lmplayground.models.DeviceCapability
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.models.resolveCapabilities
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import com.druk.lmplayground.tools.ToolRegistry
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.net.Uri
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.round

// Minimum gap between per-token haptic ticks. ~60 ms (≈16/s) reads as
// distinct typewriter taps rather than a continuous buzz on fast streams.
private const val HAPTIC_MIN_INTERVAL_MS = 60L

class ConversationViewModel(val app: Application) : AndroidViewModel(app) {

    private val llamaCpp: LlamaCpp? = (app as? App)?.llamaCpp
    private var llamaModel: LlamaModel? = null
    private var llamaSession: LlamaGenerationSession? = null
    private var generatingJob: Job? = null

    // Keep strong reference to prevent GC from closing the file descriptor
    private var modelFileHandle: StorageRepository.ModelFileHandle? = null

    private val imageStore = ChatImageStore(app)
    private val preambleCache = PreambleCacheManager(app.filesDir)
    private val notifications = InferenceNotificationUpdater(app, llamaCpp)

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
    // Set when a vision-capable model loads without its image module (mmproj),
    // offering a one-time download. Carries the model so the tap can fetch it.
    private val _visionModuleHint = MutableLiveData<ModelInfo?>(null)
    private val _supportsVision = MutableLiveData(false)
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

    // Whether to show the What's New "Set up tools" button. Shown until the
    // user has opened the Tools settings once (the flag is set there, not on
    // tap, so the button doesn't visibly vanish under the user's finger).
    // Re-read on resume so it disappears after returning from Tools settings.
    private val _showToolsSetup = MutableLiveData(!storagePreferences.toolsSetupSeen)
    val showToolsSetup: LiveData<Boolean> = _showToolsSetup

    @MainThread
    fun refreshToolsSetupVisibility() {
        _showToolsSetup.value = !storagePreferences.toolsSetupSeen
    }

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
    val visionModuleHint: LiveData<ModelInfo?> = _visionModuleHint
    val supportsVision: LiveData<Boolean> = _supportsVision
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
    private val sessionStore = ChatSessionStore(
        (app as? App)?.chatRepository,
        (app as? App)?.systemPromptRepository,
        imageStore,
    )
    private val _currentSessionId = MutableLiveData<String?>(null)
    val currentSessionId: LiveData<String?> = _currentSessionId
    val sessions: LiveData<List<ChatSessionEntity>> = sessionStore.allSessions()
    /**
     * Per-model MRU list. When the loaded model changes, switchMap swaps in
     * the corresponding query so the picker reflects "prompts I've used on
     * *this* model" with the most-recently-used one first.
     */
    val recentSystemPrompts: LiveData<List<SystemPromptEntity>> =
        _loadedModel.switchMap { model ->
            sessionStore.recentSystemPromptsForModel(model?.filename)
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
                        .map { it.copy(model = it.model.resolveCapabilities(storagePreferences)) }
                )
            }
        }
    }

    @MainThread
    fun loadModel(modelInfo: ModelInfo, forceLoad: Boolean = false) {
        val llamaCpp = llamaCpp ?: return

        // Clear any prior vision-module offer; the mmproj block below re-posts
        // it if this model loads vision-capable but without its image module.
        _visionModuleHint.value = null

        viewModelScope.launch {
            // RAM-fit check. Run BEFORE we tear down the currently-loaded
            // model so the user can cancel the warning and keep their
            // existing session intact.
            //
            // Weight repacking is controlled by the user via Settings →
            // Advanced ("Disable repack"), OFF by default. With it OFF every
            // model repacks (faster matmuls) at the cost of a second resident
            // copy of the weights; with it ON weights stay memory-mapped
            // (smaller footprint, slower decode). A model over the RAM budget
            // is never refused — but while repacking is on it can OOM-kill the
            // :llama process, so we warn once (unless repack is already off, in
            // which case the mmap-only load won't blow the budget). "Load
            // anyway" re-enters with forceLoad=true.
            val disableRepack = storagePreferences.disableRepack
            val modelFiles = withContext(Dispatchers.IO) { storageRepository.getModelFiles() }
            val fileSizeBytes = modelFiles.find { it.name == modelInfo.filename }?.sizeBytes ?: 0L
            // Pair the model with a multimodal projector present on disk (its
            // declared one, or a convention-matched sibling for custom/sideloaded
            // models). Authoritative for every load path, incl. loadModelByFilename.
            val model = ModelInfoProvider.resolveMmproj(
                modelInfo, modelFiles.map { it.name }.toSet()
            )
            val totalRamBytes = DeviceCapability.totalRamBytes(app)
            val exceedsRam = DeviceCapability.exceedsRamBudget(fileSizeBytes, totalRamBytes)
            if (!forceLoad && exceedsRam && !disableRepack) {
                _pendingRamWarning.value = RamWarning(
                    modelInfo = modelInfo,
                    neededRam = Formatter.formatFileSize(app, fileSizeBytes),
                    totalRam = Formatter.formatFileSize(app, totalRamBytes),
                )
                return@launch
            }

            _models.postValue(emptyList())
            _isModelReady.postValue(false)


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
                _loadedModel.postValue(model)
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
                        },
                        disableRepack = disableRepack,
                    )

                    // Load mmproj for vision models. The mtmd loader needs a
                    // real filesystem path, so we copy the mmproj GGUF to a
                    // temp file in app-private storage before handing it off.
                    if (model.mmprojFilename != null) {
                        _loadedModelStatus.postValue("Loading vision...")
                        val mmprojTempFile = java.io.File(app.cacheDir, "mmproj_temp.gguf")
                        val copied = storageRepository.copyModelToFile(
                            model.mmprojFilename, mmprojTempFile
                        )
                        if (copied) {
                            android.util.Log.d(
                                "ConversationVM",
                                "Loading mmproj from ${mmprojTempFile.absolutePath}"
                            )
                            llamaModel.loadMmprojModel(mmprojTempFile.absolutePath)
                            mmprojTempFile.delete()
                            android.util.Log.d(
                                "ConversationVM",
                                "mmproj loaded, supportsVision=${llamaModel.supportsVision()}"
                            )
                            _visionModuleHint.postValue(null)
                        } else {
                            android.util.Log.d(
                                "ConversationVM",
                                "mmproj file not found: ${model.mmprojFilename}"
                            )
                            // Vision-capable model loaded without its image
                            // module: offer to download it, once per model.
                            if (model.mmprojUri != null &&
                                !storagePreferences.wasVisionModuleHintShown(model.filename)
                            ) {
                                storagePreferences.setVisionModuleHintShown(model.filename)
                                _visionModuleHint.postValue(model)
                            }
                        }
                    }

                    val modelSize = llamaModel.getModelSize()
                    val modelDescription = Formatter.formatFileSize(app, modelSize)
                    // Surface "Model is loaded" + "<name> - <size>" in the
                    // FGS notification (otherwise hidden under MIN importance,
                    // but visible when the user expands the Silent group in
                    // the shade). The description line is cached so generation
                    // can later flip the title to "Generating…"/"Response
                    // ready" without re-deriving it.
                    notifications.modelLine = "${modelInfo.name} - $modelDescription"
                    notifications.modelName = modelInfo.name
                    notifications.update(
                        com.druk.lmplayground.R.string.inference_notification_loaded_title
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
                    val thinkingSupported = llamaModel.supportsThinking()
                    _supportsThinking.postValue(thinkingSupported)
                    _supportsVision.postValue(llamaModel.supportsVision())
                    val toolCallingSupported = llamaModel.supportsToolCalling()
                    _supportsToolCalling.postValue(toolCallingSupported)
                    // Cache the real, template-detected capabilities so the model
                    // list can show accurate badges for this model (and any custom
                    // GGUF) without having to load it again.
                    storagePreferences.setDetectedCaps(
                        modelInfo.filename, toolCallingSupported, thinkingSupported
                    )
                    if (toolCallingSupported) {
                        val states = mutableMapOf<String, Boolean>()
                        for (tool in toolRegistry.getAllTools()) {
                            // Per-model override wins, else the global default.
                            val enabled = storagePreferences.effectiveToolEnabled(
                                modelInfo.filename, tool.name
                            )
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
                            HistoryReplay.replayToSession(llamaSession, messages)
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
                        sessionStore.updateSessionModel(
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
     * Pre-flight check for every session-recreation path (see
     * [HistoryReplay.validateReplaySize]). Maps failures to a localized
     * one-shot [_userError] and returns false; the caller MUST then abort
     * without mutating session state.
     */
    private fun validateReplaySize(systemPrompt: String, messages: List<Message>): Boolean {
        return when (val result = HistoryReplay.validateReplaySize(systemPrompt, messages)) {
            HistoryReplay.ValidationResult.Ok -> true
            is HistoryReplay.ValidationResult.SystemPromptTooLarge -> {
                _userError.postValue(
                    app.getString(
                        com.druk.lmplayground.R.string.system_prompt_too_large,
                        result.promptBytes / 1024,
                        result.maxBytes / 1024,
                    )
                )
                false
            }
            HistoryReplay.ValidationResult.MessageTooLarge -> {
                _userError.postValue(
                    app.getString(com.druk.lmplayground.R.string.history_message_too_large)
                )
                false
            }
        }
    }

    /**
     * Swap [newSession] in as the live session after replaying [messages]
     * into it, destroying [prevSession] only once the replay succeeds (so a
     * late failure leaves the prior session intact instead of stranding the
     * UI session-less).
     *
     * Centralises the create-then-replace error handling shared by
     * [updateGenerationParams], [loadSession] and [applySystemPrompt]:
     *   - [PayloadTooLargeException]: a persisted message exceeds the binder
     *     cap; tear the new session down and keep the old one.
     *   - [InferenceUnavailableException]: the :llama service died mid-replay
     *     (or never re-connected). Previously this escaped the viewModelScope
     *     coroutine and crashed the app process — surfacing on Google Play as
     *     withService / requireConnected IUE. Now we tear the half-built
     *     session down and surface a recoverable error; the crash-recovery
     *     flow ([onInferenceCrashed]) cleans up the stale handles.
     *
     * Returns true if the swap happened, false if the caller should abort.
     */
    private fun swapInSessionWithReplay(
        newSession: LlamaGenerationSession,
        prevSession: LlamaGenerationSession?,
        messages: List<Message>,
    ): Boolean {
        try {
            if (messages.isNotEmpty()) {
                HistoryReplay.replayToSession(newSession, messages)
            }
        } catch (e: PayloadTooLargeException) {
            try { newSession.destroy() } catch (_: Throwable) {}
            _userError.postValue(
                app.getString(com.druk.lmplayground.R.string.history_message_too_large)
            )
            return false
        } catch (e: InferenceUnavailableException) {
            android.util.Log.w(
                "ConversationViewModel",
                "replayHistory failed: service unavailable", e
            )
            try { newSession.destroy() } catch (_: Throwable) {}
            _userError.postValue(
                app.getString(com.druk.lmplayground.R.string.inference_engine_unavailable)
            )
            return false
        }
        this@ConversationViewModel.llamaSession = newSession
        prevSession?.destroy()
        return true
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
                sessionStore.updateSessionParams(sessionId, params)
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
                    swapInSessionWithReplay(newSession, prevSession, messages)
                }
            }
        }
    }

    @MainThread
    fun addMessage(message: Message, imageUri: Uri? = null) {
        // Persist the image so it survives the picker URI lifetime and is kept
        // with the saved conversation (path stored on the message row below).
        val persistedImageFile = imageUri?.let { imageStore.persistImageFile(it) }
        val userMessage = if (persistedImageFile != null) {
            message.copy(imageUri = imageStore.imageContentUri(persistedImageFile))
        } else {
            message
        }

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

            // Persist user message (with the attached image path, if any)
            val sessionId = ensureSession(message)
            sessionStore.persistMessage(sessionId, userMessage, persistedImageFile?.absolutePath)

            withContext(Dispatchers.Default) {
                val llamaSession = llamaSession ?: return@withContext

                // Read + downscale the picked image off the binder thread so
                // setImageData (below) can hand the encoded bytes to the
                // native layer before addMessage. Failures degrade to a
                // text-only turn rather than aborting the message.
                val imageBytes: ByteArray? = if (persistedImageFile != null) {
                    try {
                        withContext(Dispatchers.IO) {
                            imageStore.resizeImageForVision(persistedImageFile)
                        }?.also {
                            android.util.Log.d("ConversationVM", "Image loaded: ${it.size} bytes")
                        } ?: run {
                            android.util.Log.e("ConversationVM", "Failed to decode image ${persistedImageFile.absolutePath}")
                            null
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ConversationVM", "Error reading image: ${e.message}")
                        null
                    }
                } else {
                    null
                }

                // Re-hydrate per-tool enablement from prefs before every turn so
                // a tool toggled in Settings -> Tools (global default) or a
                // model's params sheet (per-model override) takes effect on the
                // next message — without this, enabling a tool after the model
                // was loaded had no effect (the registry was only hydrated at
                // load time), so the model never saw any tools.
                _loadedModel.value?.filename?.let { filename ->
                    val states = mutableMapOf<String, Boolean>()
                    for (tool in toolRegistry.getAllTools()) {
                        val enabled = storagePreferences.effectiveToolEnabled(filename, tool.name)
                        toolRegistry.setToolEnabled(tool.name, enabled)
                        states[tool.name] = enabled
                    }
                    _toolEnabledStates.postValue(states)
                }

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
                    // Hand the encoded image to the native layer before
                    // addMessage so the multimodal preprocessor can fold it
                    // into the prompt for this turn.
                    if (imageBytes != null) {
                        llamaSession.setImageData(imageBytes)
                    }
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

                // Resolve the haptic gate once per turn: the in-app setting
                // AND the system-wide haptic toggle (a ContentResolver query
                // — too heavy to run per token).
                val hapticsAllowed = storagePreferences.hapticOnGeneration &&
                    GenerationHaptics.isSystemHapticsEnabled(app)
                val callback = object: LlamaGenerationCallback {
                    var totalTokens = 0
                    var thinkingTokenCount = 0
                    var thinkingComplete = !enableThinking
                    var modelIsThinking = enableThinking
                    // Throttle the silent token-count notification update to
                    // ~1/sec: setForegroundContent is a blocking binder call,
                    // so we must not fire it on every streamed token.
                    var lastNotifUpdateMs = 0L
                    // Throttle the per-token haptic tick so fast streams feel
                    // like rapid typing instead of one continuous buzz.
                    var lastHapticMs = 0L
                    override fun onFullResponse(response: String) {
                        totalTokens++
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastNotifUpdateMs >= 1000L) {
                            lastNotifUpdateMs = nowMs
                            notifications.update(
                                com.druk.lmplayground.R.string.inference_notification_generating_title,
                                notifications.tokensLine(totalTokens),
                            )
                        }
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

                        // Typewriter-style haptic: a light tick per *output*
                        // token. Gated on thinkingComplete so the stream only
                        // buzzes for the visible answer, never the hidden
                        // thinking. Throttled, and only while the chat is
                        // on-screen (also satisfies the OS rule that bars
                        // background vibration).
                        if (hapticsAllowed &&
                            thinkingComplete &&
                            nowMs - lastHapticMs >= HAPTIC_MIN_INTERVAL_MS &&
                            (app as? App)?.isAppInForeground == true
                        ) {
                            lastHapticMs = nowMs
                            GenerationHaptics.tick(app)
                        }

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
                //
                // Silently flip the notification to "Generating…" with a
                // starting token count; the callback above bumps the count
                // ~1/sec as tokens stream. The finally block below freezes
                // it at the final total under "Response ready".
                notifications.update(
                    com.druk.lmplayground.R.string.inference_notification_generating_title,
                    notifications.tokensLine(0),
                )
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
                        // Run the (blocking) tool execution on IO and await it so
                        // Stop can interrupt it: on cancel we abort in-flight
                        // network requests, which unblocks the call promptly.
                        val toolResults = withContext(Dispatchers.IO) {
                            val exec = async { toolRegistry.executeToolCalls(toolCallsJson) }
                            try {
                                exec.await()
                            } catch (e: CancellationException) {
                                toolRegistry.cancelInFlight()
                                throw e
                            }
                        }
                        val toolDurationMs = System.currentTimeMillis() - toolStartTime
                        android.util.Log.d(
                            "ConversationVM",
                            "Tool results (${toolDurationMs}ms): $toolResults",
                        )

                        val toolCallInfoList = ToolCallInfoMapper.buildToolCallInfoList(
                            toolCallsJson, toolResults, toolDurationMs,
                        )
                        Snapshot.withMutableSnapshot {
                            uiState.addToolCallsToLastMessage(toolCallInfoList)
                        }

                        // Force thinking on for the response phase if the
                        // model supports it, regardless of the user toggle.
                        // Gemma 4 and harmony-style models emit an empty
                        // content channel after tool calls when thinking is
                        // off — the chat would otherwise show a blank
                        // assistant bubble after every tool call. Reasoning
                        // still routes to the collapsed thinking section via
                        // the always-on DEEPSEEK extraction in the parser,
                        // so visible content stays clean. For models without
                        // a thinking mode this is a no-op (the flag is
                        // silently ignored). See testReproduceAppBehavior
                        // for the canonical repro.
                        val supportsThinking = _supportsThinking.value == true
                        val responseThinking = supportsThinking || enableThinking
                        llamaSession.submitToolResults(toolResults, responseThinking)

                        // Reset the streaming callback's per-round counters
                        // so the next generateAll() reports a fresh
                        // thinking-vs-response token split. The callback
                        // tracks WHICH phase the model is in for THIS round,
                        // so it follows responseThinking (the just-submitted
                        // flag) — not the user toggle — so the UI shows the
                        // thinking indicator while we wait for the answer.
                        callback.totalTokens = 0
                        callback.thinkingTokenCount = 0
                        callback.thinkingComplete = !responseThinking
                        callback.modelIsThinking = responseThinking

                        // Restart the thinking timer for the post-tool phase:
                        // addToolCallsToLastMessage reset thinkingStartTimeMs to 0,
                        // and the callback's modelIsThinking is pre-set true so the
                        // streaming path won't fire markThinkingStarted itself —
                        // without this the post-tool "Thinking" duration stays 0s.
                        if (responseThinking) {
                            Snapshot.withMutableSnapshot {
                                uiState.markThinkingStarted()
                            }
                        }
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
                        // Generation (or cancellation) is done — freeze the
                        // silent notification on "Response ready" with the
                        // final token count, and attach Copy/Share actions
                        // bound to the finalized response (think-tags
                        // stripped, matching the in-chat share/copy). Skipped
                        // on the superseded path above, so a newer in-flight
                        // turn's "Generating…" line is preserved.
                        val readyBody = (uiState.messages.lastOrNull()
                            ?.takeIf { it.author == "Assistant" }
                            ?.content
                            ?.let { stripThinkTags(it) })
                            ?.takeIf { it.isNotBlank() }
                        notifications.update(
                            com.druk.lmplayground.R.string.inference_notification_ready_title,
                            notifications.tokensLine(callback.totalTokens),
                            actionBody = readyBody,
                        )

                        // If the user isn't looking at the app, play a short
                        // chime so they know the answer is ready. Gated on the
                        // in-app setting, a non-blank response (so a cancelled/
                        // empty turn stays quiet), and background state; the
                        // helper itself also respects silent/vibrate/DND.
                        if (storagePreferences.soundOnCompletion &&
                            readyBody != null &&
                            (app as? App)?.isAppInForeground == false
                        ) {
                            com.druk.lmplayground.inference.ResponseSound.playIfAudible(app)
                        }

                        // Persist whatever the assistant produced — including
                        // a partially-streamed response on cancel — so
                        // reload-from-DB matches what the user saw on screen.
                        val assistantMessage = uiState.messages.lastOrNull()
                        if (assistantMessage != null && assistantMessage.author == "Assistant") {
                            try {
                                sessionStore.persistMessage(sessionId, assistantMessage)
                                sessionStore.touchSessionTimestamp(sessionId)
                                persistConversationMetadata(sessionId)
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

        val id = sessionStore.createSession(
            firstUserMessage,
            _loadedModel.value,
            _generationParams.value ?: GenerationParams(),
            _systemPrompt.value.orEmpty(),
        )
        _currentSessionId.postValue(id)
        return id
    }

    private suspend fun persistConversationMetadata(sessionId: String) {
        sessionStore.persistWebLinks(sessionId, toolRegistry.webLinkStore.snapshot())
    }

    @MainThread
    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val uiMessages = sessionStore.loadSessionMessages(sessionId) ?: return@launch
            val sessionEntity = sessionStore.getSession(sessionId)

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

            // Drop the previous conversation's web_search references; the loaded
            // session's own references (if any) are restored just below.
            toolRegistry.webLinkStore.clear()

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
                    val entity = sessionStore.findSystemPromptByText(stored)
                    _systemPromptId.value = entity?.id
                }

                // Restore web_search link references saved with this conversation
                // so the model can still web_fetch a previously-returned ref.
                toolRegistry.webLinkStore.restore(
                    ConversationMetadata.parse(sessionEntity.metadata)
                        .getStringMap(ConversationMetadata.KEY_WEB_LINKS)
                )
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
                    swapInSessionWithReplay(newSession, prevSession, uiMessages)
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
            // Fresh conversation starts with no web_search references.
            toolRegistry.webLinkStore.clear()

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
                sessionStore.updateSessionSystemPrompt(sessionId, text)
            }
        }

        // Bump per-model MRU for library-sourced picks.
        if (promptId != null) {
            val modelFilename = _loadedModel.value?.filename
            if (!modelFilename.isNullOrEmpty()) {
                viewModelScope.launch {
                    sessionStore.touchSystemPromptUsage(promptId, modelFilename)
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
                swapInSessionWithReplay(newSession, prevSession, messages)
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
        if (id == null || !sessionStore.systemPromptsAvailable) {
            applySystemPrompt(null, trimmed)
            return
        }
        viewModelScope.launch {
            if (!sessionStore.updateSystemPromptText(id, trimmed)) return@launch
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
        if (!sessionStore.systemPromptsAvailable) {
            applySystemPrompt(null, trimmed)
            return
        }
        viewModelScope.launch {
            val entity = sessionStore.createSystemPrompt(trimmed) ?: return@launch
            applySystemPrompt(entity.id, entity.text)
        }
    }

    @MainThread
    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            sessionStore.renameSession(sessionId, newTitle)
        }
    }

    @MainThread
    fun pinSession(sessionId: String, pinned: Boolean) {
        viewModelScope.launch {
            sessionStore.pinSession(sessionId, pinned)
        }
    }

    @MainThread
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            sessionStore.deleteSession(sessionId)
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

    fun dismissVisionModuleHint() {
        _visionModuleHint.value = null
    }

    /**
     * Start downloading the image module (mmproj) for the currently-hinted
     * vision model. Vision activates the next time this model is loaded (the
     * projector binds at load). No-op if storage isn't configured.
     */
    @MainThread
    fun downloadVisionModule() {
        val model = _visionModuleHint.value ?: return
        _visionModuleHint.value = null
        val storageUri = storageRepository.getStorageUri()
        if (storageUri == null) {
            android.util.Log.w("ConversationViewModel", "downloadVisionModule: storage not configured")
            return
        }
        com.druk.lmplayground.download.DownloadRepository(app)
            .startMmprojDownload(model, storageUri)
    }

    @MainThread
    fun loadModelByFilename(filename: String) {
        _sessionModelHint.value = null
        val modelInfo = ModelInfoProvider.getByFilename(filename)
            ?: ModelInfoProvider.createCustomModelInfo(filename, filename.removeSuffix(".gguf"), 0)
        loadModel(modelInfo)
    }

    fun getReport(): String? {
        // Invoked synchronously on the main thread from the token-count tap.
        // Both proxy calls go over AIDL and throw InferenceUnavailableException
        // if the :llama service crashed or hasn't bound — there's nothing to
        // report in that case, so swallow it instead of crashing the app
        // (seen on Google Play as withService / requireConnected IUE).
        return try {
            val modelReport = llamaModel?.getModelReport() ?: return null
            val sessionReport = llamaSession?.getReport() ?: return null
            modelReport + "\n" + sessionReport
        } catch (e: InferenceUnavailableException) {
            android.util.Log.w("ConversationViewModel", "getReport failed: service unavailable", e)
            null
        }
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
            _supportsVision.postValue(false)
            _supportsToolCalling.postValue(false)
            _toolEnabledStates.postValue(emptyMap())
        }
    }

    /**
     * Toggle a tool for the currently loaded model. This records a per-model
     * override (which takes precedence over the global default set in
     * Settings → Tools) so changing a tool here only affects this model.
     */
    @MainThread
    fun setToolEnabled(toolName: String, enabled: Boolean) {
        toolRegistry.setToolEnabled(toolName, enabled)
        _loadedModel.value?.filename?.let { filename ->
            storagePreferences.setToolOverride(filename, toolName, enabled)
        }
        val states = _toolEnabledStates.value.orEmpty().toMutableMap()
        states[toolName] = enabled
        _toolEnabledStates.value = states
    }

    /**
     * Set up the persistent preamble (system prompt + tools) KV cache for
     * [session] — gathers the loaded model's identity and delegates to
     * [PreambleCacheManager]. getModelSize() is a cheap AIDL call backed
     * by an in-memory llama_model field.
     */
    private fun applyPreambleCache(
        session: LlamaGenerationSession,
        toolsJson: String,
    ) {
        val modelSize = try { llamaModel?.getModelSize() ?: 0L } catch (_: Throwable) { 0L }
        preambleCache.apply(
            session,
            _loadedModel.value?.filename,
            modelSize,
            _systemPrompt.value.orEmpty(),
            toolsJson,
        )
    }

    fun resetModelList() {
        _models.postValue(emptyList())
    }

    data class RamWarning(
        val modelInfo: ModelInfo,
        val neededRam: String,
        val totalRam: String,
    )

}
