package com.druk.lmplayground.api

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.druk.lmplayground.App
import com.druk.lmplayground.api.json.ErrorCodec
import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.ErrorType
import kotlinx.coroutines.runBlocking

/**
 * The app's only exported component besides `MainActivity`: the public
 * inference API.
 *
 * Deliberately thin. Everything real lives in [ChatCompletionHandler], which
 * knows nothing about binder — that separation is what lets a loopback HTTP
 * transport be added later without touching the engine or policy layers.
 *
 * Runs in the **main process**, not `:llama`: it needs the model catalog, SAF
 * storage and the arbiter, none of which exist in the engine process (see
 * `App.onCreate`'s `ProcessUtils.isLlamaProcess()` early return). A third
 * process would need its own binding to `LlamaService` and would risk loading
 * the model twice.
 *
 * This is **not** a foreground service. `LlamaService` already promotes itself
 * while a model is loaded, and its `startForeground` call is already wrapped
 * against `ForegroundServiceStartNotAllowedException` for the Android 12+
 * background-start restriction.
 */
class ApiService : Service() {

    private val app: App? get() = application as? App

    private val identity by lazy { CallerIdentity(this) }

    private val policy: ApiAccessPolicy by lazy {
        UserToggleAccessPolicy(
            com.druk.lmplayground.storage.StoragePreferences(this)
        )
    }

    private val handler: ChatCompletionHandler? by lazy { app?.chatCompletionHandler }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IChatService.Stub() {

        override fun getApiVersion(): Int = LmPlaygroundApi.API_VERSION

        override fun getServiceInfo(): String {
            enforce(ApiAccessPolicy.Op.SERVICE_INFO, null)
            return handler?.serviceInfo() ?: throw IllegalStateException(NOT_READY)
        }

        override fun listModels(): String {
            enforce(ApiAccessPolicy.Op.LIST_MODELS, null)
            val handler = handler ?: throw IllegalStateException(NOT_READY)
            // Binder threads are allowed to block; the caller is already off
            // its own main thread (or should be — the SDK guarantees it).
            return runBlocking { handler.listModels() }
        }

        override fun createChatCompletion(
            requestJson: String,
            callback: IChatCompletionCallback,
        ): String {
            // Must be read here, synchronously on the binder thread: the
            // calling identity is thread-local to the transaction and is
            // meaningless once we hop to a coroutine.
            val callerUid = Binder.getCallingUid()

            val handler = handler
            if (handler == null) {
                emitEarlyError(callback, ApiError(NOT_READY, ErrorType.ENGINE_UNAVAILABLE))
                return ""
            }

            val decision = policy.check(
                callingUid = callerUid,
                packages = identity.packagesFor(callerUid),
                op = ApiAccessPolicy.Op.CHAT_COMPLETION,
                token = null,
            )
            if (decision !is ApiAccessPolicy.Decision.Allow) {
                emitEarlyError(callback, ApiError(
                    message = (decision as? ApiAccessPolicy.Decision.Deny)?.message
                        ?: "Access to the inference API was denied.",
                    type = ErrorType.PERMISSION_DENIED,
                ))
                return ""
            }

            // The sink is created before the request id exists, so it is bound
            // to the id the handler returns. We close that loop by letting the
            // handler own the id and giving the sink a late-bound one.
            val pending = PendingSink(callback) { id -> handler.cancel(id) }
            val requestId = handler.start(requestJson, callerUid, pending)
            pending.bind(requestId)
            return requestId
        }

        override fun cancel(requestId: String) {
            handler?.cancel(requestId)
        }

        override fun putBlob(
            pfd: ParcelFileDescriptor,
            mimeType: String,
            sizeBytes: Long,
        ): String {
            enforce(ApiAccessPolicy.Op.PUT_BLOB, null)
            val blobStore = app?.blobStore ?: return ""
            return try {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                    blobStore.put(stream, sizeBytes)
                }.orEmpty()
            } catch (t: Throwable) {
                Log.w(TAG, "putBlob failed", t)
                ""
            }
        }
    }

    /**
     * Throw a [SecurityException] for a denied non-streaming call.
     *
     * These methods return a plain String, so there is no error envelope to put
     * a structured refusal in. A SecurityException surfaces on the client as a
     * thrown exception, which is the conventional binder behaviour and what the
     * SDK's `runCatching` around the handshake already handles.
     */
    private fun enforce(op: ApiAccessPolicy.Op, token: String?) {
        val callerUid = Binder.getCallingUid()
        val decision = policy.check(callerUid, identity.packagesFor(callerUid), op, token)
        if (decision is ApiAccessPolicy.Decision.Deny) {
            throw SecurityException(decision.message)
        }
    }

    private fun emitEarlyError(callback: IChatCompletionCallback, error: ApiError) {
        runCatching { callback.onError("", ErrorCodec.encode(error)) }
    }

    /**
     * A sink whose request id is assigned after construction.
     *
     * `ChatCompletionHandler.start` mints the id and may emit a terminal error
     * before returning it (a malformed request, say), so the sink has to exist
     * first. Buffered output before [bind] is rare but must not be lost.
     */
    private class PendingSink(
        private val callback: IChatCompletionCallback,
        private val onClientDeath: (String) -> Unit,
    ) : ResponseSink {

        private var delegate: BinderResponseSink? = null
        private val buffered = mutableListOf<Pair<Kind, String>>()

        @Volatile
        override var requestId: String = ""
            private set

        private enum class Kind { CHUNK, COMPLETE, ERROR }

        @Synchronized
        fun bind(requestId: String) {
            if (delegate != null) return
            this.requestId = requestId
            // An empty id means the request was rejected synchronously and the
            // error is already buffered; deliver it with an empty id rather
            // than inventing one.
            val sink = BinderResponseSink(callback, requestId) { onClientDeath(requestId) }
            delegate = sink
            buffered.forEach { (kind, json) ->
                when (kind) {
                    Kind.CHUNK -> sink.chunk(json)
                    Kind.COMPLETE -> sink.complete(json)
                    Kind.ERROR -> sink.error(json)
                }
            }
            buffered.clear()
        }

        @Synchronized
        override fun delta(model: String, reasoning: String?, content: String?) {
            // Deltas only ever arrive after bind() — the handler cannot stream
            // before start() has returned an id. Falling back to the interface
            // default keeps that assumption from becoming a silent data loss.
            delegate?.delta(model, reasoning, content)
                ?: super.delta(model, reasoning, content)
        }

        @Synchronized
        override fun chunk(json: String) {
            delegate?.chunk(json) ?: buffered.add(Kind.CHUNK to json)
        }

        @Synchronized
        override fun complete(json: String) {
            delegate?.complete(json) ?: buffered.add(Kind.COMPLETE to json)
        }

        @Synchronized
        override fun error(json: String) {
            delegate?.error(json) ?: buffered.add(Kind.ERROR to json)
        }
    }

    private companion object {
        private const val TAG = "ApiService"
        private const val NOT_READY =
            "LM Playground is still starting up. Retry in a moment."
    }
}
