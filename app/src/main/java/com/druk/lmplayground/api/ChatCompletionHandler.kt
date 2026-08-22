package com.druk.lmplayground.api

import android.util.Log
import com.druk.lmplayground.api.json.ErrorCodec
import com.druk.lmplayground.api.json.RequestCodec
import com.druk.lmplayground.api.json.RequestFormatException
import com.druk.lmplayground.api.json.ResponseCodec
import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.ChatCompletion
import com.druk.lmplayground.api.model.ChatCompletionChunk
import com.druk.lmplayground.api.model.ChatCompletionRequest
import com.druk.lmplayground.api.model.ChatMessage
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.LmpCompletionInfo
import com.druk.lmplayground.api.model.ModelCapabilities
import com.druk.lmplayground.api.model.ModelEntry
import com.druk.lmplayground.api.model.ModelList
import com.druk.lmplayground.api.model.Role
import com.druk.lmplayground.api.model.Usage
import com.druk.lmplayground.models.resolveCapabilities
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The core of the public API, deliberately knowing nothing about binder.
 *
 * Everything transport-specific lives behind [ResponseSink]. Swapping in a
 * loopback HTTP server later means implementing that interface and mapping
 * `ApiError.httpStatus` onto a status line — no changes here, in the arbiter,
 * or in the codecs.
 */
class ChatCompletionHandler(
    private val arbiter: EngineArbiter,
    private val blobStore: BlobStore,
    private val parkedTurns: ParkedToolTurns,
    private val storageRepository: StorageRepository,
    private val storagePreferences: StoragePreferences,
    private val scope: CoroutineScope,
    private val appVersionName: String,
) {

    private val runner = ApiTurnRunner(blobStore)

    /**
     * Start a request. Returns immediately with an opaque id, or "" when the
     * request was rejected before it could start — in which case [sink] has
     * already received the error, so callers only need one error path.
     *
     * @param callerUid from `Binder.getCallingUid()`, read on the binder thread
     *        before any coroutine hop.
     */
    fun start(requestJson: String, callerUid: Int, sink: ResponseSink): String {
        val requestId = "chatcmpl-lmp-${UUID.randomUUID().toString().take(8)}"

        val request = try {
            RequestCodec.decode(requestJson)
        } catch (e: RequestFormatException) {
            sink.error(ErrorCodec.encode(e.error))
            return ""
        } catch (t: Throwable) {
            sink.error(ErrorCodec.encode(
                ApiError("Could not read the request: ${t.message}", ErrorType.INVALID_REQUEST)
            ))
            return ""
        }

        val history = try {
            ApiHistoryMapper.map(request.messages)
        } catch (e: RequestFormatException) {
            sink.error(ErrorCodec.encode(e.error))
            return ""
        }

        // Asking for an image without declaring the requirement is a common
        // mistake; infer it so the caller gets `capability_unavailable` at
        // resolution time rather than a confusing empty answer from a
        // text-only model.
        val requirements = if (history.finalImageUrl != null && !request.lmp.require.vision) {
            request.lmp.require.copy(vision = true)
        } else {
            request.lmp.require
        }

        val terminated = AtomicBoolean(false)
        var streamedContent = ""
        var streamedReasoning = ""

        fun emitError(error: ApiError) {
            if (!terminated.compareAndSet(false, true)) return
            arbiter.unregisterInFlight(requestId)
            blobStore.release(history.finalImageUrl)
            sink.error(ErrorCodec.encode(error))
        }

        val job = scope.launch {
            try {
                withTimeoutOrNull(request.lmp.timeoutMs) {
                    execute(
                        requestId = requestId,
                        request = request,
                        history = history,
                        requirements = requirements,
                        callerUid = callerUid,
                        sink = sink,
                        terminated = terminated,
                        onStreamed = { reasoning, content ->
                            if (reasoning != null) streamedReasoning += reasoning
                            if (content != null) streamedContent += content
                        },
                        onError = ::emitError,
                    )
                } ?: emitError(ApiError(
                    message = "The request exceeded its ${request.lmp.timeoutMs} ms budget.",
                    type = ErrorType.ENGINE_UNAVAILABLE,
                    code = "lmp_timeout",
                    partialContent = streamedContent.takeIf { it.isNotEmpty() },
                ))
            } catch (e: CancellationException) {
                // Cancelled by cancel(), by client death, or by the engine
                // crash collector — which has already emitted its own error.
                withContext(NonCancellable) {
                    emitError(ApiError(
                        message = "The request was cancelled.",
                        type = ErrorType.CANCELLED,
                        partialContent = streamedContent.takeIf { it.isNotEmpty() },
                    ))
                }
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "request $requestId failed", t)
                emitError(ApiError("Internal error: ${t.message}", ErrorType.INTERNAL))
            } finally {
                arbiter.unregisterInFlight(requestId)
            }
        }

        arbiter.registerInFlight(EngineArbiter.InFlight(
            requestId = requestId,
            job = job,
            fail = ::emitError,
            partialContent = { streamedContent },
        ))
        return requestId
    }

    fun cancel(requestId: String) {
        arbiter.cancelInFlight(requestId)
    }

    // ── The turn ─────────────────────────────────────────────────────────

    private suspend fun execute(
        requestId: String,
        request: ChatCompletionRequest,
        history: ApiHistoryMapper.MappedHistory,
        requirements: com.druk.lmplayground.api.model.Requirements,
        callerUid: Int,
        sink: ResponseSink,
        terminated: AtomicBoolean,
        onStreamed: (String?, String?) -> Unit,
        onError: (ApiError) -> Unit,
    ) {
        val startedMs = System.currentTimeMillis()

        arbiter.withEngine(
            requestedModel = request.model,
            requirements = requirements,
            allowLoad = request.lmp.allowLoad,
            onError = onError,
        ) { lease ->
            // A parked session from the caller's previous tool round lets us
            // resume from the live KV cache instead of replaying. Absent or
            // rejected, we fall through to a normal replay — the request always
            // carries the whole conversation.
            val answeredIds = request.messages
                .filter { it.role == Role.TOOL }
                .mapNotNull { it.toolCallId }
                .toSet()
            val resumed = parkedTurns.claim(
                token = request.lmp.continuationToken,
                callerUid = callerUid,
                modelFilename = lease.info.filename,
                answeredToolCallIds = answeredIds,
            )
            val toolResultsJson = if (resumed != null) encodeToolResults(request) else null

            val streaming = request.stream
            val deltaSink = ApiTurnRunner.DeltaSink { reasoning, content ->
                onStreamed(reasoning, content)
                // Hand the transport semantic deltas, not serialized chunks —
                // the binder sink coalesces them to stay inside the oneway
                // async buffer, and only it knows how much batching it needs.
                if (streaming && !terminated.get()) {
                    sink.delta(lease.info.filename, reasoning, content)
                }
            }

            val outcome = runner.run(
                lease = lease,
                request = request,
                history = history,
                resumeSession = resumed,
                toolResultsJson = toolResultsJson,
                sink = deltaSink,
            )

            blobStore.release(history.finalImageUrl)

            when (outcome) {
                is ApiTurnRunner.Outcome.Failed -> onError(outcome.error)

                is ApiTurnRunner.Outcome.Finished -> {
                    if (!terminated.compareAndSet(false, true)) return@withEngine
                    sink.complete(ResponseCodec.encodeCompletion(ChatCompletion(
                        id = requestId,
                        model = lease.info.filename,
                        created = startedMs / 1000,
                        message = ChatMessage(
                            role = Role.ASSISTANT,
                            content = outcome.content,
                            reasoningContent = outcome.reasoning.takeIf { it.isNotBlank() },
                        ),
                        finishReason = outcome.finishReason,
                        usage = Usage(
                            promptTokens = 0,
                            completionTokens = outcome.tokenCount,
                            totalTokens = outcome.tokenCount,
                        ),
                        lmp = completionInfo(lease, history, startedMs, outcome.reasoningTokens),
                    )))
                }

                is ApiTurnRunner.Outcome.ToolCallsRequested -> {
                    if (!terminated.compareAndSet(false, true)) return@withEngine
                    val token = parkedTurns.park(
                        session = outcome.session,
                        callerUid = callerUid,
                        modelFilename = lease.info.filename,
                        toolCallIds = outcome.calls.map { it.id }.toSet(),
                    )
                    sink.complete(ResponseCodec.encodeCompletion(ChatCompletion(
                        id = requestId,
                        model = lease.info.filename,
                        created = startedMs / 1000,
                        message = ChatMessage(
                            role = Role.ASSISTANT,
                            content = null,
                            toolCalls = outcome.calls,
                            reasoningContent = outcome.reasoning.takeIf { it.isNotBlank() },
                        ),
                        finishReason = "tool_calls",
                        usage = Usage(
                            completionTokens = outcome.tokenCount,
                            totalTokens = outcome.tokenCount,
                        ),
                        lmp = completionInfo(lease, history, startedMs, 0)
                            .copy(continuationToken = token),
                    )))
                }
            }
        }
    }

    private fun completionInfo(
        lease: EngineArbiter.Lease,
        history: ApiHistoryMapper.MappedHistory,
        startedMs: Long,
        reasoningTokens: Int,
    ) = LmpCompletionInfo(
        reasoningTokens = reasoningTokens,
        durationMs = System.currentTimeMillis() - startedMs,
        modelWasPreloaded = lease.wasPreloaded,
        headlessLoadMs = lease.headlessLoadMs,
        warnings = history.warnings,
    )

    /** Tool results in the `[{id, name, content}]` shape `submitToolResults` wants. */
    private fun encodeToolResults(request: ChatCompletionRequest): String {
        val callNames = request.messages
            .flatMap { it.toolCalls }
            .associate { it.id to it.name }
        return org.json.JSONArray().apply {
            request.messages
                .filter { it.role == Role.TOOL }
                .forEach { message ->
                    val id = message.toolCallId ?: return@forEach
                    put(org.json.JSONObject()
                        .put("id", id)
                        .put("name", callNames[id] ?: "")
                        .put("content", message.textContent()))
                }
        }.toString()
    }

    // ── listModels ───────────────────────────────────────────────────────

    suspend fun listModels(): String = withContext(Dispatchers.IO) {
        val loaded = arbiter.loadedModelFilename()
        val foregroundCaps = arbiter.foregroundCapabilities()

        val entries = arbiter.downloadedCandidates().map { candidate ->
            val info = candidate.info
            val isLoaded = info.filename == loaded
            // Capabilities are only authoritative once the GGUF's chat template
            // has been read — which happens on first load and is cached. Say so
            // rather than presenting catalog hints as facts.
            val verified = isLoaded || storagePreferences.getDetectedCaps(info.filename) != null
            ModelEntry(
                id = info.filename,
                displayName = info.name,
                downloaded = true,
                loaded = isLoaded,
                custom = info.isCustom,
                sizeBytes = candidate.sizeBytes,
                languages = info.supportedLanguages,
                capabilities = ModelCapabilities(
                    vision = if (isLoaded) foregroundCaps?.first ?: candidate.visionReady
                    else candidate.visionReady,
                    tools = if (isLoaded) foregroundCaps?.second ?: candidate.tools
                    else candidate.tools,
                    thinking = if (isLoaded) foregroundCaps?.third ?: candidate.thinking
                    else candidate.thinking,
                    verified = verified,
                    maxContext = if (isLoaded) arbiter.foregroundMaxContext() else null,
                ),
                created = info.releaseDate
                    ?.atStartOfDay(java.time.ZoneOffset.UTC)?.toEpochSecond() ?: 0,
            )
        }

        ResponseCodec.encodeModelList(ModelList(
            models = entries,
            apiVersion = LmPlaygroundApi.API_VERSION,
            loadedModel = loaded,
            engineBusy = arbiter.isBusy(),
            storageConfigured = storageRepository.isStorageConfigured(),
        ))
    }

    fun serviceInfo(): String = ResponseCodec.encodeServiceInfo(
        com.druk.lmplayground.api.model.ServiceInfo(
            apiVersion = LmPlaygroundApi.API_VERSION,
            appVersionName = appVersionName,
            features = setOf(
                LmPlaygroundApi.FEATURE_CHAT_STREAM,
                LmPlaygroundApi.FEATURE_CHAT_TOOLS,
                LmPlaygroundApi.FEATURE_CHAT_VISION,
                LmPlaygroundApi.FEATURE_MODELS_LIST,
                LmPlaygroundApi.FEATURE_BLOBS,
            ),
            maxRequestBytes = ApiLimits.MAX_REQUEST_BYTES,
            maxBlobBytes = ApiLimits.MAX_BLOB_BYTES,
        )
    )

    private companion object {
        private const val TAG = "ChatCompletionHandler"
    }
}
