package com.druk.lmplayground.api

import android.util.Log
import com.druk.llamacpp.LlamaGenerationSession
import java.util.UUID

/**
 * Keeps a session alive across a client-side tool round trip.
 *
 * **Why this exists at all.** The obvious design — treat every request as
 * stateless and replay the conversation — does not work for tool calls.
 * `LlamaGenerationSession.replayHistory` accepts only parallel user/assistant
 * string arrays in strict alternation; there is no `tool` role on the wire, and
 * no way to express an assistant turn that *carried* `tool_calls` in the
 * model's own template form. `submitToolResults` is the only path the native
 * layer supports, and it requires the session to still be parked in the
 * post-tool-call state.
 *
 * So a turn that ends in tool calls hands its session here instead of
 * destroying it, and the client gets a `continuation_token` back. Re-sending
 * the conversation with that token resumes from the live KV cache using the
 * model's real tool-response template.
 *
 * **The token is an optimization, never a requirement.** The client's request
 * still carries the complete conversation, so an expired, evicted, unknown or
 * wrong-caller token just costs a replay through
 * [ApiHistoryMapper]'s text-flattening fallback — the request still succeeds,
 * with `lmp.warnings: ["tool_history_flattened"]`.
 *
 * Bounded on purpose: each parked session holds a live `llama_context`, which
 * on a multi-billion-parameter model is hundreds of megabytes of KV cache.
 */
class ParkedToolTurns {

    private class Parked(
        val token: String,
        val session: LlamaGenerationSession,
        val callerUid: Int,
        val modelFilename: String,
        val toolCallIds: Set<String>,
        var lastUsedMs: Long,
    )

    /** Insertion-ordered so eviction is LRU without a separate structure. */
    private val parked = LinkedHashMap<String, Parked>()

    /**
     * Park [session] and return the token to hand back to the client.
     *
     * Takes ownership: the session is destroyed on expiry, eviction, or
     * [claim] failure — the caller must not destroy it itself.
     */
    @Synchronized
    fun park(
        session: LlamaGenerationSession,
        callerUid: Int,
        modelFilename: String,
        toolCallIds: Set<String>,
    ): String {
        expireStale()
        while (parked.size >= MAX_PARKED) {
            val oldest = parked.keys.firstOrNull() ?: break
            Log.i(TAG, "evicting parked tool turn $oldest (cap $MAX_PARKED)")
            parked.remove(oldest)?.destroy()
        }
        val token = "cont_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        parked[token] = Parked(
            token = token,
            session = session,
            callerUid = callerUid,
            modelFilename = modelFilename,
            toolCallIds = toolCallIds,
            lastUsedMs = System.currentTimeMillis(),
        )
        return token
    }

    /**
     * Take the session back for a continuation, removing it from the registry.
     *
     * Returns null — and the caller falls back to a full replay — when the
     * token is unknown, expired, from a different caller, issued against a
     * different model, or does not cover every tool result being submitted.
     *
     * The UID check is the important one: a token is a capability referencing a
     * live conversation, and it must not be usable by an app that overheard it.
     */
    @Synchronized
    fun claim(
        token: String?,
        callerUid: Int,
        modelFilename: String,
        answeredToolCallIds: Set<String>,
    ): LlamaGenerationSession? {
        if (token == null) return null
        expireStale()

        val entry = parked[token] ?: run {
            Log.i(TAG, "continuation token not found; falling back to replay")
            return null
        }
        if (entry.callerUid != callerUid) {
            Log.w(TAG, "continuation token presented by uid $callerUid, issued to ${entry.callerUid}")
            return null
        }
        if (entry.modelFilename != modelFilename) {
            Log.i(TAG, "continuation token was issued against ${entry.modelFilename}")
            parked.remove(token)?.destroy()
            return null
        }
        // A mismatch means the client answered a different set of calls than
        // the ones this session is parked on; resuming would desynchronise the
        // KV cache from the conversation the client believes it is having.
        if (answeredToolCallIds.isNotEmpty() && !entry.toolCallIds.containsAll(answeredToolCallIds)) {
            Log.w(TAG, "tool_call_id mismatch on continuation; falling back to replay")
            parked.remove(token)?.destroy()
            return null
        }

        parked.remove(token)
        return entry.session
    }

    /** Drop everything — used when the engine process dies under us. */
    @Synchronized
    fun clear(destroySessions: Boolean) {
        if (destroySessions) parked.values.forEach { it.destroy() }
        parked.clear()
    }

    @Synchronized
    fun size(): Int = parked.size

    private fun expireStale() {
        val cutoff = System.currentTimeMillis() - IDLE_EXPIRY_MS
        val iterator = parked.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.lastUsedMs < cutoff) {
                Log.i(TAG, "expiring parked tool turn ${entry.key}")
                entry.value.destroy()
                iterator.remove()
            }
        }
    }

    private fun Parked.destroy() {
        runCatching { session.destroy() }
    }

    companion object {
        private const val TAG = "ParkedToolTurns"

        /**
         * How long a client has to run its tools and come back. Generous enough
         * for a network-backed tool, short enough that an abandoned round trip
         * does not hold a KV cache indefinitely.
         */
        const val IDLE_EXPIRY_MS = 60_000L

        /** Each parked turn holds a live inference context — keep this small. */
        const val MAX_PARKED = 2
    }
}
