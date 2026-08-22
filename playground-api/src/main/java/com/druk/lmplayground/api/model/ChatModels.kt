package com.druk.lmplayground.api.model

/**
 * Typed view of the OpenAI-shaped payloads that cross the binder.
 *
 * These exist so app developers never hand-write JSON, while the wire format
 * stays plain JSON strings: unknown-field tolerance and additive evolution come
 * free, and the same codec classes are used by the client AND by LM Playground
 * itself, so there is exactly one definition of the schema.
 */

// ── Requests ─────────────────────────────────────────────────────────────

/** A single content part of a multimodal message. */
sealed interface ContentPart {
    data class Text(val text: String) : ContentPart

    /**
     * [url] is either a `data:image/...;base64,...` URL or an
     * `lmp-blob:<uuid>` handle returned by `putBlob`.
     */
    data class ImageUrl(val url: String) : ContentPart
}

enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
    ;

    companion object {
        fun fromWire(value: String): Role? = entries.firstOrNull { it.wire == value }
            // "developer" is OpenAI's newer name for a system message.
            ?: if (value == "developer") SYSTEM else null
    }
}

data class ToolCall(
    val id: String,
    val name: String,
    /** Raw JSON string, per the OpenAI schema — NOT a parsed object. */
    val arguments: String,
)

data class ChatMessage(
    val role: Role,
    /** Null for an assistant message that carried only tool calls. */
    val content: String? = null,
    val parts: List<ContentPart> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    /** Set on a [Role.TOOL] message; matches the id of the call it answers. */
    val toolCallId: String? = null,
    /** Thinking output, surfaced separately from [content]. */
    val reasoningContent: String? = null,
) {
    /** Text of this message, folding multimodal parts down to their text. */
    fun textContent(): String = when {
        content != null -> content
        parts.isNotEmpty() -> parts.filterIsInstance<ContentPart.Text>()
            .joinToString("\n") { it.text }
        else -> ""
    }

    fun images(): List<ContentPart.ImageUrl> = parts.filterIsInstance<ContentPart.ImageUrl>()
}

data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema object describing the parameters. */
    val parametersSchema: String,
)

/**
 * Minimum capabilities the caller needs from whichever model serves this
 * request. An omitted or `false` entry means *no constraint* — it never means
 * "must not have".
 */
data class Requirements(
    val vision: Boolean = false,
    val tools: Boolean = false,
    val thinking: Boolean = false,
    val minContext: Int = 0,
) {
    val isEmpty: Boolean get() = !vision && !tools && !thinking && minContext <= 0
}

enum class ThinkingMode(val wire: String) {
    AUTO("auto"), ON("on"), OFF("off");

    companion object {
        fun fromWire(value: String?): ThinkingMode =
            entries.firstOrNull { it.wire == value } ?: AUTO
    }
}

/** LM Playground extensions, namespaced under `"lmp"` so strict OpenAI parsers ignore them. */
data class LmpRequestOptions(
    val require: Requirements = Requirements(),
    /** `false` forbids a headless load — serve only an already-loaded model. */
    val allowLoad: Boolean = true,
    val contextSize: Int = com.druk.lmplayground.api.ApiLimits.DEFAULT_CONTEXT_SIZE,
    val thinking: ThinkingMode = ThinkingMode.AUTO,
    val thinkingBudget: Int = 0,
    val repetitionPenalty: Float? = null,
    val timeoutMs: Long = com.druk.lmplayground.api.ApiLimits.DEFAULT_TIMEOUT_MS,
    /**
     * Human-readable name of the calling app, shown to the user. Never trusted
     * on its own — LM Playground cross-checks it against the resolved package
     * label before displaying it.
     */
    val clientLabel: String? = null,
    /**
     * Handle returned with a previous `tool_calls` response. An optimization
     * hint, not required state: the request must still carry the complete
     * conversation, and an expired or unknown token just costs a replay.
     */
    val continuationToken: String? = null,
    /** Reserved for a future bearer-token access policy. */
    val authToken: String? = null,
)

data class ChatCompletionRequest(
    val messages: List<ChatMessage>,
    /** A GGUF filename, or "auto"/null to let LM Playground choose. */
    val model: String? = null,
    val stream: Boolean = true,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val minP: Float? = null,
    val seed: Int? = null,
    val maxTokens: Int? = null,
    val stop: List<String> = emptyList(),
    val tools: List<ToolDefinition> = emptyList(),
    val lmp: LmpRequestOptions = LmpRequestOptions(),
) {
    class Builder {
        private val messages = mutableListOf<ChatMessage>()
        private var model: String? = null
        private var stream: Boolean = true
        private var temperature: Float? = null
        private var topP: Float? = null
        private var topK: Int? = null
        private var minP: Float? = null
        private var seed: Int? = null
        private var maxTokens: Int? = null
        private var stop: List<String> = emptyList()
        private var tools: List<ToolDefinition> = emptyList()
        private var lmp: LmpRequestOptions = LmpRequestOptions()

        fun system(text: String) = apply { messages += ChatMessage(Role.SYSTEM, text) }
        fun user(text: String) = apply { messages += ChatMessage(Role.USER, text) }
        fun assistant(text: String) = apply { messages += ChatMessage(Role.ASSISTANT, text) }
        fun message(message: ChatMessage) = apply { messages += message }
        fun messages(all: List<ChatMessage>) = apply { messages += all }

        /** A user turn carrying text plus an image reference. */
        fun userWithImage(text: String, imageUrl: String) = apply {
            messages += ChatMessage(
                role = Role.USER,
                parts = listOf(ContentPart.Text(text), ContentPart.ImageUrl(imageUrl)),
            )
        }

        fun model(value: String?) = apply { model = value }
        fun stream(value: Boolean) = apply { stream = value }
        fun temperature(value: Float) = apply { temperature = value }
        fun topP(value: Float) = apply { topP = value }
        fun topK(value: Int) = apply { topK = value }
        fun minP(value: Float) = apply { minP = value }
        fun seed(value: Int) = apply { seed = value }
        fun maxTokens(value: Int) = apply { maxTokens = value }
        fun stop(vararg value: String) = apply { stop = value.toList() }
        fun tools(value: List<ToolDefinition>) = apply { tools = value }
        fun lmp(value: LmpRequestOptions) = apply { lmp = value }
        fun require(value: Requirements) = apply { lmp = lmp.copy(require = value) }

        fun build() = ChatCompletionRequest(
            messages = messages.toList(),
            model = model,
            stream = stream,
            temperature = temperature,
            topP = topP,
            topK = topK,
            minP = minP,
            seed = seed,
            maxTokens = maxTokens,
            stop = stop,
            tools = tools,
            lmp = lmp,
        )
    }
}

// ── Responses ────────────────────────────────────────────────────────────

/** One streamed `chat.completion.chunk`. */
data class ChatCompletionChunk(
    val id: String,
    val model: String,
    val contentDelta: String? = null,
    /** Thinking output. Kept out of [contentDelta] so callers can hide it. */
    val reasoningDelta: String? = null,
    val finishReason: String? = null,
)

data class Usage(
    /** Always 0 — the engine does not expose a prompt token count over AIDL. */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)

data class LmpCompletionInfo(
    val reasoningTokens: Int = 0,
    val durationMs: Long = 0,
    val modelWasPreloaded: Boolean = false,
    val headlessLoadMs: Long = 0,
    val continuationToken: String? = null,
    val warnings: List<String> = emptyList(),
)

data class ChatCompletion(
    val id: String,
    val model: String,
    val created: Long,
    val message: ChatMessage,
    /** `stop | length | tool_calls | cancelled | error` */
    val finishReason: String,
    val usage: Usage = Usage(),
    val lmp: LmpCompletionInfo = LmpCompletionInfo(),
)

// ── Models list ──────────────────────────────────────────────────────────

data class ModelCapabilities(
    val vision: Boolean = false,
    val tools: Boolean = false,
    val thinking: Boolean = false,
    /**
     * True when these came from the GGUF's chat template (the model has been
     * loaded at least once), false when they are catalog hints. Requirements
     * are matched best-effort against hints and authoritatively re-checked
     * after a load — see PROTOCOL.md §capabilities.
     */
    val verified: Boolean = false,
    /** Non-null only when [verified]; determining it requires a load. */
    val maxContext: Int? = null,
)

data class ModelEntry(
    /** The GGUF filename; this is the `model` value to pass in a request. */
    val id: String,
    val displayName: String,
    val downloaded: Boolean,
    val loaded: Boolean,
    val custom: Boolean,
    val sizeBytes: Long,
    val languages: List<String>,
    val capabilities: ModelCapabilities,
    val created: Long = 0,
)

data class ModelList(
    val models: List<ModelEntry>,
    val apiVersion: Int,
    val loadedModel: String?,
    val engineBusy: Boolean,
    val storageConfigured: Boolean,
)

// ── Errors ───────────────────────────────────────────────────────────────

/** Error `type` values. Each maps to a documented HTTP status. */
object ErrorType {
    const val INVALID_REQUEST = "invalid_request_error"
    const val PERMISSION_DENIED = "permission_denied"
    const val MODEL_NOT_FOUND = "model_not_found"
    const val CAPABILITY_UNAVAILABLE = "capability_unavailable"
    const val MODEL_MISMATCH = "model_mismatch"
    const val PAYLOAD_TOO_LARGE = "payload_too_large"
    const val NO_MODEL_AVAILABLE = "no_model_available"
    const val NO_MODEL_LOADED = "no_model_loaded"
    const val ENGINE_BUSY = "engine_busy"
    const val ENGINE_UNAVAILABLE = "engine_unavailable"
    const val CANCELLED = "cancelled"
    const val INTERNAL = "internal_error"

    /**
     * The HTTP status this error type would carry over an HTTP transport.
     * Emitted into the envelope so a future loopback server is a pure mapping
     * with no new logic.
     */
    fun httpStatus(type: String): Int = when (type) {
        INVALID_REQUEST -> 400
        PERMISSION_DENIED -> 403
        MODEL_NOT_FOUND -> 404
        CAPABILITY_UNAVAILABLE, MODEL_MISMATCH -> 409
        PAYLOAD_TOO_LARGE -> 413
        CANCELLED -> 499
        NO_MODEL_AVAILABLE, NO_MODEL_LOADED, ENGINE_BUSY, ENGINE_UNAVAILABLE -> 503
        else -> 500
    }
}

data class CandidateModel(
    val id: String,
    val displayName: String,
    val downloaded: Boolean,
)

data class ApiError(
    val message: String,
    val type: String,
    val param: String? = null,
    val code: String? = null,
    val httpStatus: Int = ErrorType.httpStatus(type),
    /** Models that would satisfy the request, when the chosen one could not. */
    val candidates: List<CandidateModel> = emptyList(),
    /** The model the user has loaded, when that is why we refused. */
    val loadedModelId: String? = null,
    /** Whatever streamed before the failure, for `engine_unavailable`. */
    val partialContent: String? = null,
    val retryAfterMs: Long? = null,
) {
    val isRetryable: Boolean
        get() = type == ErrorType.ENGINE_BUSY || type == ErrorType.ENGINE_UNAVAILABLE
}

/** Thrown by the SDK's suspend entry points when a request fails. */
class ApiException(val error: ApiError) : Exception(error.message)

// ── Service info ─────────────────────────────────────────────────────────

data class ServiceInfo(
    val apiVersion: Int,
    val appVersionName: String,
    val features: Set<String>,
    val maxRequestBytes: Int,
    val maxBlobBytes: Long,
) {
    fun supports(feature: String): Boolean = feature in features
}
