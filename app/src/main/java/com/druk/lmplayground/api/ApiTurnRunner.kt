package com.druk.lmplayground.api

import android.util.Log
import com.druk.llamacpp.InferenceUnavailableException
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaGenerationSession
import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.ChatCompletionRequest
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.ThinkingMode
import com.druk.lmplayground.api.model.ToolCall
import com.druk.lmplayground.api.model.ToolDefinition
import com.druk.lmplayground.conversation.GenerationParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Drives one API generation turn against a leased model.
 *
 * Mirrors [com.druk.lmplayground.conversation.GenerationCoordinator] — the same
 * `setTools` → `setImageData` → `addMessage` → `generateAll` ordering, the same
 * guaranteed cleanup — with three differences that matter:
 *
 * - The session is **created and destroyed per request**, on the leased model.
 *   The user's session is never touched.
 * - Tool calls are handed **back to the caller** rather than executed here. LM
 *   Playground's own tools (web search, page fetch, JavaScript) are not exposed
 *   to third parties.
 * - The persistent preamble KV cache is deliberately **not** enabled: it is
 *   keyed on (model, system prompt, tools), and arbitrary third-party system
 *   prompts would thrash the cache directory the chat depends on.
 */
class ApiTurnRunner(
    private val blobStore: BlobStore,
) {

    /** How a turn ended. */
    sealed interface Outcome {
        /** Natural stop. */
        data class Finished(
            val content: String,
            val reasoning: String,
            val tokenCount: Int,
            val reasoningTokens: Int,
            val finishReason: String,
        ) : Outcome

        /**
         * The model emitted tool calls. [session] is still parked in the
         * post-tool-call state so the caller can resume it with
         * `submitToolResults` — the only path the native layer supports for
         * continuing a tool round trip with exact KV continuity.
         */
        data class ToolCallsRequested(
            val calls: List<ToolCall>,
            val reasoning: String,
            val tokenCount: Int,
            val session: LlamaGenerationSession,
        ) : Outcome

        data class Failed(val error: ApiError, val partialContent: String) : Outcome
    }

    /** Streamed progress. Invoked from a binder thread. */
    fun interface DeltaSink {
        fun onDelta(reasoning: String?, content: String?)
    }

    /**
     * Run a turn on a fresh session.
     *
     * @param resumeSession a parked session from a previous tool round, or null
     *        to create one and replay [history] into it.
     */
    suspend fun run(
        lease: EngineArbiter.Lease,
        request: ChatCompletionRequest,
        history: ApiHistoryMapper.MappedHistory,
        resumeSession: LlamaGenerationSession? = null,
        toolResultsJson: String? = null,
        sink: DeltaSink,
    ): Outcome = withContext(Dispatchers.Default) {
        val tracker = StreamDeltaTracker()
        var session: LlamaGenerationSession? = resumeSession
        val weOwnSession = resumeSession == null
        var handedOff = false

        try {
            if (session == null) {
                session = createAndPrime(lease, request, history, tracker)
                    ?: return@withContext Outcome.Failed(
                        ApiError(
                            "Could not create an inference session on " +
                                "'${lease.info.name}'. The engine may be out of memory — try a " +
                                "smaller lmp.context_size.",
                            ErrorType.ENGINE_UNAVAILABLE,
                        ),
                        "",
                    )
            } else if (toolResultsJson != null) {
                // Resuming a parked tool turn: feed the results back into the
                // live KV cache using the model's own tool-response template.
                val enableThinking = resolveThinking(request, lease)
                session.submitToolResults(toolResultsJson, enableThinking)
            }

            val outcome = generate(session, lease, request, tracker, sink)
            // A parked tool turn hands the live session to the caller —
            // ParkedToolTurns owns it from here and will destroy it on expiry.
            if (outcome is Outcome.ToolCallsRequested) handedOff = true
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: InferenceUnavailableException) {
            Log.w(TAG, "turn failed: engine unavailable", e)
            Outcome.Failed(
                ApiError(
                    message = "The inference engine became unavailable mid-request. The model " +
                        "may have been unloaded.",
                    type = ErrorType.ENGINE_UNAVAILABLE,
                    code = "lmp_model_unloaded",
                    partialContent = tracker.fullContent.takeIf { it.isNotEmpty() },
                ),
                tracker.fullContent,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "turn failed", t)
            Outcome.Failed(
                ApiError("Internal error: ${t.message}", ErrorType.INTERNAL),
                tracker.fullContent,
            )
        } finally {
            // Cleanup must run even when the turn was cancelled mid-stream, so
            // it goes through NonCancellable — otherwise the session leaks a
            // llama_context every time a client hits Stop.
            val toDestroy = session
            if (weOwnSession && !handedOff && toDestroy != null) {
                withContext(NonCancellable) {
                    runCatching { toDestroy.destroy() }
                }
            }
        }
    }

    private suspend fun createAndPrime(
        lease: EngineArbiter.Lease,
        request: ChatCompletionRequest,
        history: ApiHistoryMapper.MappedHistory,
        tracker: StreamDeltaTracker,
    ): LlamaGenerationSession? {
        val params = samplerParams(request, lease)
        val session = lease.model.createSession(
            params.contextSize,
            params.temperature,
            params.topP,
            params.repetitionPenalty,
            params.topK,
            params.minP,
            params.seed,
            params.thinkingBudget,
            history.systemPrompt,
        ) ?: return null

        // Tools must be configured before addMessage — the native side folds
        // them into the rendered prompt.
        val toolsJson = if (request.tools.isEmpty()) "[]" else encodeTools(request.tools)
        session.setTools(toolsJson)

        if (history.replayUser.isNotEmpty()) {
            session.replayHistory(
                history.replayUser.toTypedArray(),
                history.replayAssistant.toTypedArray(),
            )
        }

        // Vision: the encoded bytes must be staged BEFORE addMessage so the
        // multimodal preprocessor can fold the image into this turn's prompt.
        history.finalImageUrl?.let { url ->
            val bytes = blobStore.resolveImage(url)
            if (bytes != null) {
                session.setImageData(bytes)
            } else {
                Log.w(TAG, "could not resolve image reference; continuing text-only")
            }
        }

        session.addMessage(history.finalUserContent, resolveThinking(request, lease))
        return session
    }

    private suspend fun generate(
        session: LlamaGenerationSession,
        lease: EngineArbiter.Lease,
        request: ChatCompletionRequest,
        tracker: StreamDeltaTracker,
        sink: DeltaSink,
    ): Outcome {
        val maxTokens = request.maxTokens ?: Int.MAX_VALUE
        var hitLimit = false
        var hitStop = false

        val callback = object : LlamaGenerationCallback {
            override fun onFullResponse(response: String) {
                val delta = tracker.update(response)
                if (!delta.isEmpty) sink.onDelta(delta.reasoning, delta.content)

                if (tracker.tokenCount >= maxTokens) hitLimit = true
                // The sampler has no stop-sequence parameter, so `stop` is
                // enforced here by scanning the accumulated visible text.
                if (request.stop.any { it.isNotEmpty() && tracker.fullContent.contains(it) }) {
                    hitStop = true
                }
            }
        }

        val status = session.generateAll(callback)

        // Release whatever the tracker held back for the separator rewrite.
        tracker.flush().let { if (!it.isEmpty) sink.onDelta(it.reasoning, it.content) }

        if (status == STATUS_TOOL_CALLS) {
            val calls = parseToolCalls(session.getToolCallsJson())
            if (calls.isNotEmpty()) {
                // The session stays alive — run() sees ToolCallsRequested and
                // skips its destroy so ParkedToolTurns can take ownership.
                return Outcome.ToolCallsRequested(
                    calls = calls,
                    reasoning = tracker.fullReasoning,
                    tokenCount = tracker.tokenCount,
                    session = session,
                )
            }
        }

        val content = truncateAtStop(tracker.fullContent, request.stop)
        return Outcome.Finished(
            content = content,
            reasoning = tracker.fullReasoning,
            tokenCount = tracker.tokenCount,
            reasoningTokens = tracker.fullReasoning.length.coerceAtMost(tracker.tokenCount),
            finishReason = when {
                hitLimit -> "length"
                hitStop -> "stop"
                status == 0 -> "stop"
                else -> "stop"
            },
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun samplerParams(
        request: ChatCompletionRequest,
        lease: EngineArbiter.Lease,
    ): GenerationParams {
        val defaults = GenerationParams()
        // Cap the requested context at what the model was trained for; asking
        // for more just wastes KV memory (and this is a second context sitting
        // alongside the user's).
        val requested = request.lmp.contextSize.coerceAtLeast(512)
        val contextSize = if (lease.maxContext > 0) {
            requested.coerceAtMost(lease.maxContext)
        } else {
            requested
        }
        return GenerationParams(
            contextSize = contextSize,
            temperature = request.temperature ?: defaults.temperature,
            topP = request.topP ?: defaults.topP,
            repetitionPenalty = request.lmp.repetitionPenalty ?: defaults.repetitionPenalty,
            topK = request.topK ?: defaults.topK,
            minP = request.minP ?: defaults.minP,
            seed = request.seed ?: defaults.seed,
            thinkingBudget = request.lmp.thinkingBudget.takeIf { it > 0 } ?: (contextSize / 4),
        )
    }

    private fun resolveThinking(
        request: ChatCompletionRequest,
        lease: EngineArbiter.Lease,
    ): Boolean = when (request.lmp.thinking) {
        ThinkingMode.ON -> true
        ThinkingMode.OFF -> false
        // "auto" follows the model: enabling it on a model without a thinking
        // mode is a silent no-op, and leaving it off on one that always thinks
        // just means the reasoning arrives unlabelled.
        ThinkingMode.AUTO -> lease.thinking
    }

    private fun encodeTools(tools: List<ToolDefinition>): String =
        JSONArray().apply {
            tools.forEach { tool ->
                put(JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description)
                        .put("parameters", JSONObject(tool.parametersSchema))))
            }
        }.toString()

    private fun parseToolCalls(json: String): List<ToolCall> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val call = array.optJSONObject(i) ?: return@mapNotNull null
            val name = call.optString("name")
            if (name.isBlank()) return@mapNotNull null
            ToolCall(
                id = call.optString("id").takeIf { it.isNotBlank() } ?: "call_$i",
                name = name,
                arguments = call.optString("arguments", "{}"),
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "could not parse tool calls: $json", t)
        emptyList()
    }

    private fun truncateAtStop(text: String, stop: List<String>): String {
        var result = text
        for (sequence in stop) {
            if (sequence.isEmpty()) continue
            val index = result.indexOf(sequence)
            if (index >= 0) result = result.substring(0, index)
        }
        return result
    }

    private companion object {
        private const val TAG = "ApiTurnRunner"

        /** `generateAll` returns 2 when the model emitted tool calls. */
        const val STATUS_TOOL_CALLS = 2
    }
}
