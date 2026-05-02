package com.druk.llamacpp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AIDL-proxy view of a generation session.
 *
 * The legacy `external fun generate(callback): Int` (which sampled exactly
 * one token per call) is gone. Generation now runs entirely service-side
 * in a single AIDL round trip — see [generateAll]. The historic
 * [LlamaGenerationCallback.onFullResponse] contract is preserved by
 * accumulating deltas in this class.
 */
class LlamaGenerationSession internal constructor(
    private val client: InferenceClient,
    internal val sessionId: Int,
) {
    fun addMessage(message: String, enableThinking: Boolean) {
        InferenceLimits.requireWithinBudget(message, "user message")
        client.requireConnected().addMessage(sessionId, message, enableThinking)
    }

    /**
     * Replay a chat history into this session.
     *
     * The whole conversation may be much larger than a single binder
     * transaction can carry, so under the hood this does a `begin`,
     * one `appendHistoryPair` per turn, then `finalize` — each call
     * is its own transaction. Per-message validation still applies
     * (each individual message must fit inside [InferenceLimits.MAX_PAYLOAD_BYTES]).
     */
    fun replayHistory(userMessages: Array<String>, assistantMessages: Array<String>) {
        require(userMessages.size == assistantMessages.size) {
            "userMessages and assistantMessages must have the same length " +
                "(got ${userMessages.size} vs ${assistantMessages.size})"
        }
        if (userMessages.isEmpty()) return

        // Pre-validate every pair so we fail fast before sending any AIDL
        // calls — we don't want to half-replay a chat and then explode.
        for (i in userMessages.indices) {
            InferenceLimits.requireWithinBudget(userMessages[i], "user message #$i")
            InferenceLimits.requireWithinBudget(assistantMessages[i], "assistant message #$i")
        }

        val svc = client.requireConnected()
        svc.beginReplayHistory(sessionId)
        for (i in userMessages.indices) {
            // One string per AIDL call — keeps every transaction safely
            // under the binder cap even when each message sits at the
            // 700 KB ceiling.
            svc.appendReplayUser(sessionId, userMessages[i])
            svc.appendReplayAssistant(sessionId, assistantMessages[i])
        }
        svc.finalizeReplayHistory(sessionId)
    }

    fun printReport() {
        client.requireConnected().printSessionReport(sessionId)
    }

    fun getReport(): String = client.requireConnected().getSessionReport(sessionId)

    fun destroy() {
        try { client.requireConnected().destroySession(sessionId) } catch (_: Throwable) {}
    }

    /**
     * Drives a full generation in a single AIDL call.
     *
     * Suspends until the service signals `onGenerationFinished`. Each
     * delta arriving from the service is appended to a buffer and forwarded
     * to [callback].onFullResponse, preserving the original (full
     * accumulated string) contract for existing call sites.
     *
     * Cancellation: if the calling coroutine is cancelled (Stop button),
     * the proxy issues `cancelGeneration` and then **waits** for the
     * service worker to actually emit `onGenerationFinished` before
     * re-throwing CancellationException. Without that wait, the worker
     * could keep firing `onResponseDelta` after the caller has already
     * finalized and persisted the assistant message — leaving the saved
     * text out of sync with the screen.
     *
     * Return value: 0 on natural stop, non-zero status from the service.
     */
    suspend fun generateAll(callback: LlamaGenerationCallback): Int {
        val svc = client.requireConnected()
        val finished = CompletableDeferred<Int>()
        val buf = StringBuilder()
        val cb = object : ILlamaGenerationCallback.Stub() {
            override fun onResponseDelta(delta: String) {
                val full = synchronized(buf) {
                    buf.append(delta)
                    buf.toString()
                }
                callback.onFullResponse(full)
            }
            override fun onGenerationFinished(statusCode: Int) {
                finished.complete(statusCode)
            }
        }

        svc.startGeneration(sessionId, cb)
        return try {
            finished.await()
        } catch (e: CancellationException) {
            // Stop tapped (or job cancelled). Tell the service to abort
            // and synchronously wait for its worker to acknowledge —
            // otherwise tokens may still trickle in after we return.
            try { svc.cancelGeneration(sessionId) } catch (_: Throwable) {}
            withContext(NonCancellable) {
                withTimeoutOrNull(GEN_CANCEL_DRAIN_MS) { finished.await() }
            }
            throw e
        }
    }

    private companion object {
        // Worst-case wait for the service worker to exit after cancel.
        // Bounded by the same prompt-eval latency the service-side join
        // budget protects against (see GenerationWorker.join), plus
        // headroom for the oneway onGenerationFinished round trip.
        const val GEN_CANCEL_DRAIN_MS: Long = 30_000
    }
}
