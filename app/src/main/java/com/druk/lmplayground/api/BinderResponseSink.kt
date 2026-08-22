package com.druk.lmplayground.api

import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forwards a request's output to a client's `IChatCompletionCallback`.
 *
 * Two things here are not incidental:
 *
 * **Chunk coalescing.** `oneway` binder transactions share a ~1 MB *per-process*
 * asynchronous buffer. Firing one `onChunk` per token at 40 tok/s to a client
 * that is slow to return will overflow it, and the symptom is a
 * `TransactionTooLargeException` on a 120-byte payload — a genuinely confusing
 * failure to debug. So deltas are batched until [FLUSH_INTERVAL_MS] has passed
 * or [FLUSH_CHARS] have accumulated, and always flushed before the terminal
 * callback. (The internal chat path gets away with per-token deltas because its
 * consumer is the app itself.)
 *
 * **Client death.** If the client process dies mid-generation nothing else
 * would stop the engine, and the CPU would stay pegged on a request nobody is
 * waiting for. The callback binder's death recipient cancels the request.
 */
class BinderResponseSink(
    private val callback: IChatCompletionCallback,
    override val requestId: String,
    private val onClientDeath: () -> Unit,
) : ResponseSink {

    private val terminated = AtomicBoolean(false)
    private var deathRecipient: IBinder.DeathRecipient? = null

    /** Guards the pending buffers; chunks arrive from the generation thread. */
    private val lock = Any()
    private var pendingContent = StringBuilder()
    private var pendingReasoning = StringBuilder()
    private var lastFlushMs = 0L
    private var chunkTemplate: String? = null

    init {
        val recipient = IBinder.DeathRecipient {
            Log.i(TAG, "client for $requestId died; cancelling")
            onClientDeath()
        }
        runCatching {
            callback.asBinder().linkToDeath(recipient, 0)
            deathRecipient = recipient
        }
    }

    /**
     * Buffer a delta, emitting when the batch is big or old enough.
     *
     * Overrides the default pass-through: coalescing needs the *semantic*
     * deltas, not pre-serialized JSON.
     */
    override fun delta(model: String, reasoning: String?, content: String?) {
        val toSend = synchronized(lock) {
            reasoning?.let { pendingReasoning.append(it) }
            content?.let { pendingContent.append(it) }
            chunkTemplate = model

            val now = System.currentTimeMillis()
            val pendingChars = pendingReasoning.length + pendingContent.length
            val due = pendingChars >= FLUSH_CHARS || now - lastFlushMs >= FLUSH_INTERVAL_MS
            if (!due || pendingChars == 0) return
            lastFlushMs = now
            drainLocked(model)
        }
        toSend.forEach { chunk(it) }
    }

    /** Emit anything still buffered. Called before the terminal callback. */
    fun flushPending() {
        val toSend = synchronized(lock) {
            val model = chunkTemplate ?: return
            if (pendingReasoning.isEmpty() && pendingContent.isEmpty()) return
            drainLocked(model)
        }
        toSend.forEach { chunk(it) }
    }

    /**
     * Build the chunks for whatever is buffered and reset.
     *
     * Reasoning and content go out as separate chunks: the OpenAI delta object
     * can technically carry both, but keeping them apart means a client that
     * only understands `content` sees a clean stream with no interleaving.
     */
    private fun drainLocked(model: String): List<String> {
        val chunks = mutableListOf<String>()
        if (pendingReasoning.isNotEmpty()) {
            chunks += com.druk.lmplayground.api.json.ResponseCodec.encodeChunk(
                com.druk.lmplayground.api.model.ChatCompletionChunk(
                    id = requestId, model = model,
                    reasoningDelta = pendingReasoning.toString(),
                )
            )
            pendingReasoning = StringBuilder()
        }
        if (pendingContent.isNotEmpty()) {
            chunks += com.druk.lmplayground.api.json.ResponseCodec.encodeChunk(
                com.druk.lmplayground.api.model.ChatCompletionChunk(
                    id = requestId, model = model,
                    contentDelta = pendingContent.toString(),
                )
            )
            pendingContent = StringBuilder()
        }
        return chunks
    }

    override fun chunk(json: String) {
        if (terminated.get()) return
        try {
            callback.onChunk(requestId, json)
        } catch (t: Throwable) {
            // A dead client is normal, not exceptional — the death recipient
            // is already cancelling the request.
            Log.d(TAG, "onChunk failed for $requestId: ${t.message}")
        }
    }

    override fun complete(json: String) {
        flushPending()
        if (!terminated.compareAndSet(false, true)) return
        unlink()
        runCatching { callback.onComplete(requestId, json) }
            .onFailure { Log.d(TAG, "onComplete failed for $requestId: ${it.message}") }
    }

    override fun error(json: String) {
        flushPending()
        if (!terminated.compareAndSet(false, true)) return
        unlink()
        runCatching { callback.onError(requestId, json) }
            .onFailure { Log.d(TAG, "onError failed for $requestId: ${it.message}") }
    }

    private fun unlink() {
        deathRecipient?.let { recipient ->
            runCatching { callback.asBinder().unlinkToDeath(recipient, 0) }
        }
        deathRecipient = null
    }

    private companion object {
        private const val TAG = "BinderResponseSink"

        /** ~20 chunks/second — smooth to read, far inside the async buffer. */
        const val FLUSH_INTERVAL_MS = 50L

        /** Flush early on a fast stream so long replies stay responsive. */
        const val FLUSH_CHARS = 24
    }
}
