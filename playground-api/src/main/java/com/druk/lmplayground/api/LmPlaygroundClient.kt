package com.druk.lmplayground.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.druk.lmplayground.api.json.ErrorCodec
import com.druk.lmplayground.api.json.RequestCodec
import com.druk.lmplayground.api.json.ResponseCodec
import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.ApiException
import com.druk.lmplayground.api.model.ChatCompletion
import com.druk.lmplayground.api.model.ChatCompletionChunk
import com.druk.lmplayground.api.model.ChatCompletionRequest
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.ModelList
import com.druk.lmplayground.api.model.ServiceInfo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Client SDK for the LM Playground inference API.
 *
 * The point of this class is that migrating from an OpenAI-compatible HTTP
 * server should change your transport and nothing else: you keep the same
 * request shape, the same streaming deltas, the same tool-call round trip.
 *
 * ```kotlin
 * val client = LmPlaygroundClient(context)
 * client.connect()
 * client.chatCompletion(request).collect { event ->
 *     when (event) {
 *         is ChatEvent.Delta     -> ui.append(event.content, event.reasoning)
 *         is ChatEvent.ToolCalls -> ui.runToolsThenResend(event.completion)
 *         is ChatEvent.Done      -> ui.finish(event.completion)
 *         is ChatEvent.Failed    -> ui.showError(event.error)
 *     }
 * }
 * ```
 *
 * Cancelling collection cancels the request service-side. Binder death fails
 * in-flight requests with `engine_unavailable` and flips [state] so the UI can
 * show a reconnecting banner.
 */
class LmPlaygroundClient(context: Context) {

    private val appContext = context.applicationContext

    sealed interface State {
        /** Nothing tried yet, or [disconnect] was called. */
        object Idle : State
        object Connecting : State
        data class Connected(val target: ServiceTarget, val info: ServiceInfo) : State
        /** LM Playground is not installed, or is too old to expose the API. */
        data class Unavailable(val reason: Reason) : State
        /** Bound, then the service (or its process) went away. */
        data class Disconnected(val target: ServiceTarget) : State

        enum class Reason { NOT_INSTALLED, VERSION_TOO_OLD, BIND_REFUSED }
    }

    /** A resolved LM Playground install exposing the API. */
    data class ServiceTarget(val packageName: String, val className: String) {
        val component: ComponentName get() = ComponentName(packageName, className)
        val isDebugBuild: Boolean get() = packageName.endsWith(".debug")
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile private var service: IChatService? = null
    private var connection: ServiceConnection? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    /**
     * Find every installed LM Playground exposing the API.
     *
     * Resolves by ACTION rather than package name on purpose: the debug build
     * uses `applicationIdSuffix = ".debug"`, so a hardcoded package silently
     * fails against a debug install. Release installs sort first.
     *
     * Requires a `<queries>` entry in the caller's manifest (minSdk 30 here, so
     * package-visibility filtering always applies):
     * ```xml
     * <queries><intent>
     *   <action android:name="com.druk.lmplayground.api.BIND_CHAT_SERVICE"/>
     * </intent></queries>
     * ```
     */
    fun discover(): List<ServiceTarget> =
        appContext.packageManager
            .queryIntentServices(Intent(LmPlaygroundApi.ACTION_BIND), 0)
            .map { ServiceTarget(it.serviceInfo.packageName, it.serviceInfo.name) }
            .sortedBy { it.isDebugBuild }

    /**
     * Bind and handshake. Suspends until connected or until we know we can't
     * be. Idempotent — calling it while already connected is a no-op.
     *
     * @return the connected service info, or null if LM Playground is missing,
     *         too old, or refused the bind. [state] carries the reason.
     */
    suspend fun connect(): ServiceInfo? {
        (_state.value as? State.Connected)?.let { return it.info }

        val target = discover().firstOrNull()
        if (target == null) {
            _state.value = State.Unavailable(State.Reason.NOT_INSTALLED)
            return null
        }

        _state.value = State.Connecting
        val binder = bindAndAwait(target)
        if (binder == null) {
            _state.value = State.Unavailable(State.Reason.BIND_REFUSED)
            return null
        }

        val proxy = IChatService.Stub.asInterface(binder)
        service = proxy
        installDeathRecipient(binder, target)

        // Handshake before anything else. A newer client calling an unknown
        // transaction code on an older service gets an empty reply parcel that
        // blows up in readException() — so version-gate first, always.
        val info = try {
            val version = proxy.apiVersion
            if (version < LmPlaygroundApi.API_VERSION) {
                _state.value = State.Unavailable(State.Reason.VERSION_TOO_OLD)
                return null
            }
            ResponseCodec.decodeServiceInfo(proxy.serviceInfo)
        } catch (t: Throwable) {
            Log.w(TAG, "handshake failed", t)
            _state.value = State.Unavailable(State.Reason.VERSION_TOO_OLD)
            return null
        }

        _state.value = State.Connected(target, info)
        return info
    }

    fun disconnect() {
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
        service = null
        deathRecipient = null
        _state.value = State.Idle
    }

    /** OpenAI-shaped model list, with per-model capabilities. */
    suspend fun listModels(): ModelList = withContext(Dispatchers.IO) {
        val proxy = requireService()
        ResponseCodec.decodeModelList(proxy.listModels())
    }

    /**
     * Stage an image out-of-band, bypassing the ~700 KB binder payload cap
     * (file descriptors are not counted against transaction size).
     *
     * @return an `lmp-blob:<uuid>` handle to use as an `image_url`.
     */
    suspend fun putImage(bytes: ByteArray, mimeType: String = "image/jpeg"): String =
        withContext(Dispatchers.IO) {
            val proxy = requireService()
            require(bytes.size <= ApiLimits.MAX_BLOB_BYTES) {
                "Image is ${bytes.size} bytes; the limit is ${ApiLimits.MAX_BLOB_BYTES}."
            }
            // A pipe keeps the bytes off disk entirely. The write side runs on
            // its own thread because the pipe buffer is small (~64 KB) and the
            // service reads concurrently.
            val (read, write) = ParcelFileDescriptor.createPipe()
            Thread({
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) }
            }, "lmp-blob-writer").start()
            read.use { proxy.putBlob(it, mimeType, bytes.size.toLong()) }
                .takeIf { it.isNotEmpty() }
                ?: throw ApiException(
                    ApiError("LM Playground rejected the image upload.", ErrorType.INVALID_REQUEST)
                )
        }

    /** Convenience overload for an image already on disk. */
    suspend fun putImage(file: File, mimeType: String = "image/jpeg"): String =
        withContext(Dispatchers.IO) {
            val proxy = requireService()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                proxy.putBlob(pfd, mimeType, file.length())
            }.takeIf { it.isNotEmpty() }
                ?: throw ApiException(
                    ApiError("LM Playground rejected the image upload.", ErrorType.INVALID_REQUEST)
                )
        }

    /**
     * Run a chat completion, streaming events until a terminal one.
     *
     * The flow always ends with exactly one of [ChatEvent.Done],
     * [ChatEvent.ToolCalls] or [ChatEvent.Failed] — it never completes
     * silently. Cancelling collection issues `cancel(requestId)` service-side.
     */
    fun chatCompletion(request: ChatCompletionRequest): Flow<ChatEvent> = callbackFlow {
        val proxy = service
        if (proxy == null) {
            trySend(ChatEvent.Failed(ApiError(
                "Not connected to LM Playground. Call connect() first.",
                ErrorType.ENGINE_UNAVAILABLE,
            )))
            close()
            return@callbackFlow
        }

        // Guard against a service that misbehaves and sends a second terminal
        // callback: the flow must not try to emit after close().
        val terminated = AtomicBoolean(false)

        val callback = object : IChatCompletionCallback.Stub() {
            override fun onChunk(requestId: String, chunkJson: String) {
                if (terminated.get()) return
                val chunk = runCatching { ResponseCodec.decodeChunk(chunkJson) }.getOrNull()
                    ?: return
                trySend(ChatEvent.Delta(chunk))
            }

            override fun onComplete(requestId: String, completionJson: String) {
                if (!terminated.compareAndSet(false, true)) return
                val completion = runCatching { ResponseCodec.decodeCompletion(completionJson) }
                    .getOrNull()
                if (completion == null) {
                    trySend(ChatEvent.Failed(ApiError(
                        "Malformed completion from LM Playground.", ErrorType.INTERNAL,
                    )))
                } else if (completion.finishReason == FINISH_TOOL_CALLS) {
                    trySend(ChatEvent.ToolCalls(completion))
                } else {
                    trySend(ChatEvent.Done(completion))
                }
                close()
            }

            override fun onError(requestId: String, errorJson: String) {
                if (!terminated.compareAndSet(false, true)) return
                trySend(ChatEvent.Failed(ErrorCodec.decode(errorJson)))
                close()
            }
        }

        val requestId = try {
            proxy.createChatCompletion(RequestCodec.encode(request), callback)
        } catch (t: Throwable) {
            // The service (or the whole app process) died between our null
            // check and this transaction.
            if (terminated.compareAndSet(false, true)) {
                trySend(ChatEvent.Failed(ApiError(
                    "LM Playground is unavailable: ${t.message}", ErrorType.ENGINE_UNAVAILABLE,
                )))
            }
            close()
            return@callbackFlow
        }

        awaitClose {
            // Reached on cancellation as well as on normal completion; cancel()
            // is idempotent and safe after the request already finished.
            if (requestId.isNotEmpty()) {
                runCatching { proxy.cancel(requestId) }
            }
        }
    }

    /**
     * Non-streaming convenience wrapper: runs the request and returns the
     * terminal completion.
     *
     * @throws ApiException on any failure, so callers get one error path.
     */
    suspend fun chatCompletionAwait(request: ChatCompletionRequest): ChatCompletion {
        val event = chatCompletion(request.copy(stream = false)).first { it.isTerminal }
        return when (event) {
            is ChatEvent.Done -> event.completion
            is ChatEvent.ToolCalls -> event.completion
            is ChatEvent.Failed -> throw ApiException(event.error)
            is ChatEvent.Delta -> error("Delta is not terminal")
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun requireService(): IChatService = service
        ?: throw ApiException(ApiError(
            "Not connected to LM Playground. Call connect() first.",
            ErrorType.ENGINE_UNAVAILABLE,
        ))

    private suspend fun bindAndAwait(target: ServiceTarget): IBinder? =
        suspendCancellableCoroutine { cont: CancellableContinuation<IBinder?> ->
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (cont.isActive) cont.resume(binder)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    service = null
                    _state.value = State.Disconnected(target)
                    if (cont.isActive) cont.resume(null)
                }
            }
            connection = conn

            // BIND_IMPORTANT raises LM Playground's process importance while
            // our UI is visible, which makes it much less likely to be evicted
            // (and its :llama child with it) mid-generation.
            val bound = appContext.bindService(
                Intent(LmPlaygroundApi.ACTION_BIND).setComponent(target.component),
                conn,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
            )
            if (!bound && cont.isActive) {
                connection = null
                runCatching { appContext.unbindService(conn) }
                cont.resume(null)
            }

            cont.invokeOnCancellation {
                connection = null
                runCatching { appContext.unbindService(conn) }
            }
        }

    private fun installDeathRecipient(binder: IBinder, target: ServiceTarget) {
        val recipient = IBinder.DeathRecipient {
            Log.w(TAG, "LM Playground binder died")
            service = null
            _state.value = State.Disconnected(target)
        }
        runCatching {
            binder.linkToDeath(recipient, 0)
            deathRecipient = recipient
        }
    }

    private companion object {
        private const val TAG = "LmPlaygroundClient"
        private const val FINISH_TOOL_CALLS = "tool_calls"
    }
}

/** One event from an in-flight chat completion. */
sealed interface ChatEvent {

    val isTerminal: Boolean get() = this !is Delta

    /** A streamed token batch. Exactly one of the two deltas is non-null. */
    data class Delta(val chunk: ChatCompletionChunk) : ChatEvent {
        val content: String? get() = chunk.contentDelta
        val reasoning: String? get() = chunk.reasoningDelta
    }

    /** Natural end of the turn. */
    data class Done(val completion: ChatCompletion) : ChatEvent

    /**
     * The model wants tools run. Execute them in YOUR process, then re-send the
     * whole conversation with `role: "tool"` messages appended and
     * `lmp.continuationToken` set to
     * `completion.lmp.continuationToken` — that lets LM Playground resume from
     * the live KV cache instead of replaying the conversation.
     */
    data class ToolCalls(val completion: ChatCompletion) : ChatEvent

    data class Failed(val error: ApiError) : ChatEvent
}
