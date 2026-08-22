package com.druk.lmplayground.api

import android.app.Application
import android.util.Log
import com.druk.llamacpp.InferenceClient
import com.druk.llamacpp.InferenceState
import com.druk.llamacpp.LlamaCpp
import com.druk.llamacpp.LlamaModel
import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.Requirements
import com.druk.lmplayground.inference.ModelRuntime
import com.druk.lmplayground.models.DeviceCapability
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.resolveCapabilities
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Arbitrates access to the single loaded model between the user's chat and
 * third-party API callers.
 *
 * Two invariants make this safe:
 *
 * 1. **The user's session is never shared.** An API request always creates its
 *    own `LlamaGenerationSession` on the same `LlamaModel`. `LlamaService`
 *    claims its generation-worker slot *per session*
 *    (`SessionEntry.worker`, CAS-claimed), so the two never contend for the
 *    same `llama_context`, and two contexts over one read-only `llama_model` is
 *    a supported llama.cpp pattern. The user's KV cache is untouched.
 *
 * 2. **The user never waits for an API caller.** [engineMutex] serialises API
 *    turns against each other only. Before taking it, an API request yields to
 *    an in-flight chat turn for a bounded window and then proceeds anyway —
 *    both generating is slower for each, but blocking the user's Send button
 *    behind a background app would be indefensible.
 *
 * Application-scoped, constructed in `App.onCreate`. Implements
 * [ModelRuntime.SharedModelSink] so `ModelRuntime` — the single owner of the
 * native handles — publishes every model transition here.
 */
class EngineArbiter(
    private val app: Application,
    private val llamaCpp: LlamaCpp?,
    private val inferenceClient: InferenceClient?,
    private val storageRepository: StorageRepository,
    private val storagePreferences: StoragePreferences,
    private val scope: CoroutineScope,
) : ModelRuntime.SharedModelSink {

    /** The model the user has loaded, as published by [ModelRuntime]. */
    private data class Foreground(
        val model: LlamaModel,
        val info: ModelInfo,
        val vision: Boolean,
        val tools: Boolean,
        val thinking: Boolean,
        val maxContext: Int,
    )

    @Volatile private var foreground: Foreground? = null
    @Volatile private var userBusy: Boolean = false

    /** Serialises API generation turns against each other. Never held by the UI. */
    private val engineMutex = Mutex()

    private val headless = HeadlessModelManager(
        app = app,
        llamaCpp = llamaCpp,
        storageRepository = storageRepository,
        scope = scope,
    )

    /**
     * In-flight API requests, so a `:llama` crash can fail them immediately.
     * Keyed by requestId.
     */
    private val inFlight = ConcurrentHashMap<String, InFlight>()

    class InFlight(
        val requestId: String,
        val job: Job,
        /** Emits the terminal error and tears the request down. */
        val fail: (ApiError) -> Unit,
        /** Whatever has streamed so far, for `lmp.partial_content`. */
        val partialContent: () -> String,
    )

    /** A model held for the duration of one API turn. */
    class Lease(
        val model: LlamaModel,
        val info: ModelInfo,
        val vision: Boolean,
        val tools: Boolean,
        val thinking: Boolean,
        val maxContext: Int,
        /** True when we used the model the user already had loaded. */
        val wasPreloaded: Boolean,
        val headlessLoadMs: Long,
    )

    init {
        observeEngineCrashes()
    }

    // ── ModelRuntime.SharedModelSink ─────────────────────────────────────

    override fun publishForegroundModel(
        model: LlamaModel?,
        info: ModelInfo?,
        thinking: Boolean,
        vision: Boolean,
        toolCalling: Boolean,
        maxContext: Int,
    ) {
        foreground = if (model != null && info != null) {
            Log.i(TAG, "foreground model published: ${info.filename} " +
                "(vision=$vision tools=$toolCalling thinking=$thinking ctx=$maxContext)")
            Foreground(model, info, vision, toolCalling, thinking, maxContext)
        } else {
            Log.i(TAG, "foreground model cleared")
            null
        }
    }

    override fun setUserBusy(busy: Boolean) {
        userBusy = busy
    }

    override suspend fun releaseHeadless() {
        // Bounded: the user's load must not stall behind our teardown. If the
        // unload is slow we let it finish on the app scope and proceed — the
        // worst case is a transient double memory footprint, which is still
        // better than a UI that appears hung.
        val released = withTimeoutOrNull(RELEASE_HEADLESS_TIMEOUT_MS) {
            headless.unload()
            true
        }
        if (released == null) {
            Log.w(TAG, "headless release timed out; continuing on the app scope")
            scope.launch { headless.unload() }
        }
    }

    // ── Public state for listModels() ────────────────────────────────────

    fun loadedModelFilename(): String? = foreground?.info?.filename

    fun isBusy(): Boolean = engineMutex.isLocked || userBusy

    /** Capabilities of the loaded model, for the `verified` flag in listModels. */
    fun foregroundCapabilities(): Triple<Boolean, Boolean, Boolean>? =
        foreground?.let { Triple(it.vision, it.tools, it.thinking) }

    fun foregroundMaxContext(): Int? = foreground?.maxContext

    // ── Request registry ─────────────────────────────────────────────────

    fun registerInFlight(request: InFlight) {
        inFlight[request.requestId] = request
    }

    fun unregisterInFlight(requestId: String) {
        inFlight.remove(requestId)
    }

    fun cancelInFlight(requestId: String) {
        inFlight[requestId]?.job?.cancel()
    }

    // ── The lease ────────────────────────────────────────────────────────

    /**
     * Resolve a model, hold the engine, and run [block] against it.
     *
     * Returns the block's value, or a [Resolution.Failure]-derived error. The
     * caller emits whatever comes back; this function never throws for an
     * expected condition.
     */
    suspend fun <T> withEngine(
        requestedModel: String?,
        requirements: Requirements,
        allowLoad: Boolean,
        onError: (ApiError) -> T,
        block: suspend (Lease) -> T,
    ): T {
        if (llamaCpp == null || inferenceClient == null) {
            return onError(ApiError(
                "The inference engine is not available in this process.",
                ErrorType.ENGINE_UNAVAILABLE,
            ))
        }

        // Yield to the user's chat turn for a bounded window. We deliberately
        // proceed afterwards rather than failing: a long chat reply must not
        // make every API request time out.
        if (userBusy) {
            withTimeoutOrNull(USER_YIELD_MS) {
                while (userBusy) delay(USER_POLL_MS)
            }
        }

        // Acquire with an owner token so the timeout cannot strand the lock.
        // withTimeoutOrNull can, in principle, fire concurrently with the block
        // completing — which would leave us having acquired the mutex while
        // reporting that we didn't, wedging the engine for every later request.
        // holdsLock lets us detect and undo exactly that.
        val owner = Any()
        val locked = withTimeoutOrNull(QUEUE_WAIT_MS) {
            engineMutex.lock(owner)
            true
        }
        if (locked == null) {
            if (engineMutex.holdsLock(owner)) engineMutex.unlock(owner)
            return onError(ApiError(
                message = "Another request is using the engine. Retry shortly.",
                type = ErrorType.ENGINE_BUSY,
                retryAfterMs = QUEUE_WAIT_MS,
            ))
        }

        try {
            val lease = when (val resolution = resolveLocked(requestedModel, requirements, allowLoad)) {
                is ApiModelResolver.Resolution.Failure -> return onError(resolution.error)
                is ApiModelResolver.Resolution.UseForeground -> Lease(
                    model = foreground?.model ?: return onError(modelVanished()),
                    info = resolution.snapshot.info,
                    vision = resolution.snapshot.vision,
                    tools = resolution.snapshot.tools,
                    thinking = resolution.snapshot.thinking,
                    maxContext = resolution.snapshot.maxContext,
                    wasPreloaded = true,
                    headlessLoadMs = 0,
                )
                is ApiModelResolver.Resolution.LoadHeadless -> {
                    val started = System.currentTimeMillis()
                    val loaded = headless.ensureLoaded(
                        resolution.candidate.info,
                        resolution.disableRepack,
                    ) ?: return onError(ApiError(
                        message = "Failed to load '${resolution.candidate.info.name}'.",
                        type = ErrorType.ENGINE_UNAVAILABLE,
                    ))

                    // Authoritative capability check: catalog flags are hints
                    // until the GGUF's chat template has actually been read.
                    // Cache what we learned either way so the next request
                    // resolves correctly without repeating this load.
                    val caps = loaded.capabilities()
                    storagePreferences.setDetectedCaps(
                        resolution.candidate.info.filename, caps.tools, caps.thinking,
                    )
                    val unmet = unmetAfterLoad(caps, requirements)
                    if (unmet != null) {
                        headless.unload()
                        return onError(ApiError(
                            message = "'${resolution.candidate.info.name}' turned out not to " +
                                "support $unmet once loaded. Its real capabilities are now " +
                                "cached, so listModels() will report them.",
                            type = ErrorType.CAPABILITY_UNAVAILABLE,
                            param = "lmp.require.$unmet",
                        ))
                    }

                    Lease(
                        model = loaded.model,
                        info = resolution.candidate.info,
                        vision = caps.vision,
                        tools = caps.tools,
                        thinking = caps.thinking,
                        maxContext = caps.maxContext,
                        wasPreloaded = false,
                        headlessLoadMs = System.currentTimeMillis() - started,
                    )
                }
            }
            return block(lease)
        } finally {
            engineMutex.unlock(owner)
            // Restart the idle-unload timer only after the turn is done, so a
            // burst of requests keeps the model warm.
            headless.touch()
        }
    }

    private fun resolveLocked(
        requestedModel: String?,
        requirements: Requirements,
        allowLoad: Boolean,
    ): ApiModelResolver.Resolution {
        val snapshot = foreground?.let {
            ApiModelResolver.ForegroundSnapshot(
                info = it.info,
                vision = it.vision,
                tools = it.tools,
                thinking = it.thinking,
                maxContext = it.maxContext,
            )
        }
        return ApiModelResolver.resolve(
            requestedModel = requestedModel,
            requirements = requirements,
            foreground = snapshot,
            downloaded = downloadedCandidates(),
            allowLoad = allowLoad,
            totalRamBytes = DeviceCapability.totalRamBytes(app),
        )
    }

    /**
     * Every downloaded model with its best-known capabilities, resolved the
     * same way the model picker does it: catalog entry → mmproj pairing against
     * what is actually on disk → template-detected caps cached from a previous
     * load.
     */
    fun downloadedCandidates(): List<ApiModelResolver.Candidate> {
        val files = storageRepository.getModelFiles()
        val onDisk = files.map { it.name }.toSet()
        val sizeByName = files.associate { it.name to it.sizeBytes }

        val customModels = files
            .filter { it.name !in ModelInfoProvider.knownFilenames }
            .mapNotNull { file ->
                val meta = storagePreferences.getCustomModelMetadata(file.name)
                ModelInfoProvider.createCustomModelInfo(
                    filename = file.name,
                    name = meta?.first ?: file.displayName,
                    sizeBytes = file.sizeBytes,
                )
            }

        return ModelInfoProvider.getModelsWithStatus(onDisk, customModels)
            .filter { it.isDownloaded }
            .map { status ->
                val resolved = status.model.resolveCapabilities(storagePreferences)
                ApiModelResolver.Candidate(
                    info = resolved,
                    sizeBytes = sizeByName[resolved.filename] ?: 0L,
                    // Declaring vision is not enough — the projector has to be
                    // on disk, or the load silently produces a text-only model.
                    visionReady = status.model.isVision && status.isMmprojDownloaded,
                    tools = resolved.supportsTools,
                    thinking = resolved.supportsThinking,
                )
            }
    }

    private fun unmetAfterLoad(
        caps: HeadlessModelManager.Capabilities,
        requirements: Requirements,
    ): String? = when {
        requirements.vision && !caps.vision -> "vision"
        requirements.tools && !caps.tools -> "tools"
        requirements.thinking && !caps.thinking -> "thinking"
        requirements.minContext > 0 && caps.maxContext < requirements.minContext -> "min_context"
        else -> null
    }

    private fun modelVanished() = ApiError(
        message = "The model was unloaded while the request was starting.",
        type = ErrorType.ENGINE_UNAVAILABLE,
        code = "lmp_model_unloaded",
    )

    // ── Crash handling ───────────────────────────────────────────────────

    /**
     * Fail in-flight requests the moment `:llama` dies.
     *
     * This is mandatory, not defensive. `LlamaGenerationSession.generateAll`
     * awaits a `CompletableDeferred` that is only completed by the service's
     * `onGenerationFinished` — with **no timeout**. If the service process is
     * gone, that callback never arrives and the request hangs forever. The chat
     * path escapes only because `ConversationViewModel.onInferenceCrashed`
     * cancels its job; we need the same signal.
     *
     * We cancel **without joining**: `generateAll`'s cancellation path sits in
     * `withContext(NonCancellable) { withTimeoutOrNull(30_000) { … } }` waiting
     * for a worker that will never answer, and the client should not wait 30
     * seconds for an error we already know about.
     */
    private fun observeEngineCrashes() {
        val client = inferenceClient ?: return
        scope.launch {
            client.state.collect { state ->
                if (state !is InferenceState.Crashed) return@collect
                Log.w(TAG, "engine crashed; failing ${inFlight.size} in-flight API request(s)")
                foreground = null
                headless.dropAfterCrash()
                val snapshot = inFlight.values.toList()
                inFlight.clear()
                for (request in snapshot) {
                    request.fail(ApiError(
                        message = "The inference engine crashed mid-request.",
                        type = ErrorType.ENGINE_UNAVAILABLE,
                        code = "lmp_engine_crashed",
                        partialContent = request.partialContent().takeIf { it.isNotEmpty() },
                    ))
                    request.job.cancel()
                }
            }
        }
    }

    private companion object {
        private const val TAG = "EngineArbiter"

        /** How long a queued API request waits for the engine before giving up. */
        const val QUEUE_WAIT_MS = 15_000L

        /** How long an API request yields to an in-flight chat turn. */
        const val USER_YIELD_MS = 20_000L
        const val USER_POLL_MS = 250L

        /** Cap on stalling the user's model load behind a headless unload. */
        const val RELEASE_HEADLESS_TIMEOUT_MS = 3_000L
    }
}
