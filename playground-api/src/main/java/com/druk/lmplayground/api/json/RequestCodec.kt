package com.druk.lmplayground.api.json

import com.druk.lmplayground.api.ApiLimits
import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.ChatCompletionRequest
import com.druk.lmplayground.api.model.ChatMessage
import com.druk.lmplayground.api.model.ContentPart
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.LmpRequestOptions
import com.druk.lmplayground.api.model.Requirements
import com.druk.lmplayground.api.model.Role
import com.druk.lmplayground.api.model.ThinkingMode
import com.druk.lmplayground.api.model.ToolCall
import com.druk.lmplayground.api.model.ToolDefinition
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Serializes and parses the OpenAI-shaped chat-completions request body.
 *
 * Used by BOTH sides: the client SDK encodes with it, LM Playground decodes
 * with it. One definition of the schema, so the two can never drift.
 *
 * Uses platform `org.json`, matching the repo convention (ToolRegistry,
 * ConversationMetadata, ToolCallInfoMapper) — no serialization dependency.
 */
object RequestCodec {

    /** Fields we deliberately reject rather than silently ignore. */
    private val UNSUPPORTED_FIELDS = listOf(
        "response_format" to "structured output is not supported",
        "logprobs" to "log probabilities are not exposed by the engine",
        "top_logprobs" to "log probabilities are not exposed by the engine",
        "functions" to "the legacy functions field is not supported; use tools",
        "function_call" to "the legacy function_call field is not supported; use tool_choice",
    )

    fun encode(request: ChatCompletionRequest): String = JSONObject().apply {
        put("model", request.model ?: "auto")
        put("messages", JSONArray().apply {
            request.messages.forEach { put(encodeMessage(it)) }
        })
        put("stream", request.stream)
        request.temperature?.let { put("temperature", it.toDouble()) }
        request.topP?.let { put("top_p", it.toDouble()) }
        request.topK?.let { put("top_k", it) }
        request.minP?.let { put("min_p", it.toDouble()) }
        request.seed?.let { put("seed", it) }
        request.maxTokens?.let { put("max_tokens", it) }
        if (request.stop.isNotEmpty()) {
            put("stop", JSONArray().apply { request.stop.forEach { put(it) } })
        }
        if (request.tools.isNotEmpty()) {
            put("tools", JSONArray().apply {
                request.tools.forEach { put(encodeTool(it)) }
            })
            put("tool_choice", "auto")
        }
        put("lmp", encodeLmp(request.lmp))
    }.toString()

    /**
     * Parse a request body.
     *
     * @throws RequestFormatException with a ready-to-emit [ApiError] on any
     *         malformed or unsupported input. Callers turn that straight into
     *         the error envelope — there is no partially-valid state.
     */
    fun decode(json: String): ChatCompletionRequest {
        if (ApiLimits.exceedsRequestBudget(json)) {
            throw RequestFormatException(
                ApiError(
                    message = "Request body is ${ApiLimits.byteCost(json) / 1024} KB; " +
                        "the limit is ${ApiLimits.MAX_REQUEST_BYTES / 1024} KB. " +
                        "Use putBlob() for images instead of inlining them.",
                    type = ErrorType.PAYLOAD_TOO_LARGE,
                )
            )
        }

        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw RequestFormatException(
                ApiError("Request body is not valid JSON: ${e.message}", ErrorType.INVALID_REQUEST)
            )
        }

        // Be honest about what we can't do rather than quietly producing
        // output that doesn't match what the caller asked for.
        for ((field, why) in UNSUPPORTED_FIELDS) {
            if (root.has(field) && !root.isNull(field)) {
                throw RequestFormatException(
                    ApiError("`$field` is not supported: $why", ErrorType.INVALID_REQUEST, param = field)
                )
            }
        }
        if (root.optInt("n", 1) != 1) {
            throw RequestFormatException(
                ApiError("`n` must be 1; multiple choices are not supported.",
                    ErrorType.INVALID_REQUEST, param = "n")
            )
        }

        val messagesArray = root.optJSONArray("messages")
        if (messagesArray == null || messagesArray.length() == 0) {
            throw RequestFormatException(
                ApiError("`messages` must be a non-empty array.",
                    ErrorType.INVALID_REQUEST, param = "messages")
            )
        }
        val messages = (0 until messagesArray.length()).map { i ->
            val obj = messagesArray.optJSONObject(i)
                ?: throw RequestFormatException(
                    ApiError("messages[$i] is not an object.",
                        ErrorType.INVALID_REQUEST, param = "messages[$i]")
                )
            decodeMessage(obj, i)
        }

        val modelRaw = root.optString("model").takeIf { it.isNotBlank() }
        return ChatCompletionRequest(
            messages = messages,
            model = modelRaw?.takeIf { it != "auto" },
            stream = root.optBoolean("stream", true),
            temperature = root.optNumber("temperature")?.toFloat(),
            topP = root.optNumber("top_p")?.toFloat(),
            topK = root.optNumber("top_k")?.toInt(),
            minP = root.optNumber("min_p")?.toFloat(),
            seed = root.optNumber("seed")?.toInt(),
            maxTokens = root.optNumber("max_tokens")?.toInt(),
            stop = decodeStop(root),
            tools = decodeTools(root),
            lmp = decodeLmp(root.optJSONObject("lmp")),
        )
    }

    // ── Messages ─────────────────────────────────────────────────────────

    private fun encodeMessage(message: ChatMessage): JSONObject = JSONObject().apply {
        put("role", message.role.wire)
        when {
            message.parts.isNotEmpty() -> put("content", JSONArray().apply {
                message.parts.forEach { part ->
                    put(when (part) {
                        is ContentPart.Text -> JSONObject()
                            .put("type", "text")
                            .put("text", part.text)
                        is ContentPart.ImageUrl -> JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", part.url))
                    })
                }
            })
            // An assistant turn that produced only tool calls has a null
            // content per the OpenAI schema — preserve that exactly.
            message.content == null && message.toolCalls.isNotEmpty() -> put("content", JSONObject.NULL)
            else -> put("content", message.content.orEmpty())
        }
        if (message.toolCalls.isNotEmpty()) {
            put("tool_calls", JSONArray().apply {
                message.toolCalls.forEach { call ->
                    put(JSONObject()
                        .put("id", call.id)
                        .put("type", "function")
                        .put("function", JSONObject()
                            .put("name", call.name)
                            .put("arguments", call.arguments)))
                }
            })
        }
        message.toolCallId?.let { put("tool_call_id", it) }
        message.reasoningContent?.let { put("reasoning_content", it) }
    }

    private fun decodeMessage(obj: JSONObject, index: Int): ChatMessage {
        val roleWire = obj.optString("role")
        val role = Role.fromWire(roleWire)
            ?: throw RequestFormatException(
                ApiError("messages[$index].role `$roleWire` is not a known role.",
                    ErrorType.INVALID_REQUEST, param = "messages[$index].role")
            )

        var content: String? = null
        var parts: List<ContentPart> = emptyList()
        when (val raw = obj.opt("content")) {
            null, JSONObject.NULL -> content = null
            is JSONArray -> parts = decodeParts(raw, index)
            else -> content = raw.toString()
        }

        val toolCalls = obj.optJSONArray("tool_calls")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val call = array.optJSONObject(i) ?: return@mapNotNull null
                val function = call.optJSONObject("function") ?: return@mapNotNull null
                ToolCall(
                    id = call.optString("id", "call_$i"),
                    name = function.optString("name"),
                    arguments = function.optString("arguments", "{}"),
                )
            }
        }.orEmpty()

        return ChatMessage(
            role = role,
            content = content,
            parts = parts,
            toolCalls = toolCalls,
            toolCallId = obj.optString("tool_call_id").takeIf { it.isNotBlank() },
            reasoningContent = obj.optString("reasoning_content").takeIf { it.isNotBlank() },
        )
    }

    private fun decodeParts(array: JSONArray, messageIndex: Int): List<ContentPart> =
        (0 until array.length()).mapNotNull { i ->
            val part = array.optJSONObject(i) ?: return@mapNotNull null
            when (part.optString("type")) {
                "text" -> ContentPart.Text(part.optString("text"))
                "image_url" -> {
                    val url = part.optJSONObject("image_url")?.optString("url").orEmpty()
                    if (url.isBlank()) {
                        throw RequestFormatException(
                            ApiError("messages[$messageIndex].content[$i].image_url.url is empty.",
                                ErrorType.INVALID_REQUEST,
                                param = "messages[$messageIndex].content[$i]")
                        )
                    }
                    ContentPart.ImageUrl(url)
                }
                // Unknown part types are skipped, not fatal: this is the one
                // place where forward compatibility beats strictness, since
                // OpenAI keeps adding part kinds (audio, file, ...).
                else -> null
            }
        }

    // ── Tools ────────────────────────────────────────────────────────────

    private fun encodeTool(tool: ToolDefinition): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", tool.name)
            .put("description", tool.description)
            .put("parameters", JSONObject(tool.parametersSchema)))

    private fun decodeTools(root: JSONObject): List<ToolDefinition> {
        val array = root.optJSONArray("tools") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val function = array.optJSONObject(i)?.optJSONObject("function") ?: return@mapNotNull null
            val name = function.optString("name")
            if (name.isBlank()) return@mapNotNull null
            ToolDefinition(
                name = name,
                description = function.optString("description"),
                parametersSchema = function.optJSONObject("parameters")?.toString()
                    ?: """{"type":"object","properties":{}}""",
            )
        }
    }

    private fun decodeStop(root: JSONObject): List<String> = when (val raw = root.opt("stop")) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> (0 until raw.length()).map { raw.optString(it) }.filter { it.isNotEmpty() }
        else -> listOf(raw.toString()).filter { it.isNotEmpty() }
    }

    // ── lmp extensions ───────────────────────────────────────────────────

    private fun encodeLmp(options: LmpRequestOptions): JSONObject = JSONObject().apply {
        if (!options.require.isEmpty) {
            put("require", JSONObject().apply {
                if (options.require.vision) put("vision", true)
                if (options.require.tools) put("tools", true)
                if (options.require.thinking) put("thinking", true)
                if (options.require.minContext > 0) put("min_context", options.require.minContext)
            })
        }
        put("allow_load", options.allowLoad)
        put("context_size", options.contextSize)
        put("thinking", options.thinking.wire)
        if (options.thinkingBudget > 0) put("thinking_budget", options.thinkingBudget)
        options.repetitionPenalty?.let { put("repetition_penalty", it.toDouble()) }
        put("timeout_ms", options.timeoutMs)
        options.clientLabel?.let { put("client_label", it) }
        options.continuationToken?.let { put("continuation_token", it) }
        options.authToken?.let { put("auth_token", it) }
    }

    private fun decodeLmp(obj: JSONObject?): LmpRequestOptions {
        if (obj == null) return LmpRequestOptions()
        val require = obj.optJSONObject("require")
        return LmpRequestOptions(
            require = Requirements(
                vision = require?.optBoolean("vision", false) ?: false,
                tools = require?.optBoolean("tools", false) ?: false,
                thinking = require?.optBoolean("thinking", false) ?: false,
                minContext = require?.optInt("min_context", 0) ?: 0,
            ),
            allowLoad = obj.optBoolean("allow_load", true),
            contextSize = obj.optInt("context_size", ApiLimits.DEFAULT_CONTEXT_SIZE),
            thinking = ThinkingMode.fromWire(obj.optString("thinking").takeIf { it.isNotBlank() }),
            thinkingBudget = obj.optInt("thinking_budget", 0),
            repetitionPenalty = obj.optNumber("repetition_penalty")?.toFloat(),
            timeoutMs = obj.optLong("timeout_ms", ApiLimits.DEFAULT_TIMEOUT_MS),
            clientLabel = obj.optString("client_label").takeIf { it.isNotBlank() },
            continuationToken = obj.optString("continuation_token").takeIf { it.isNotBlank() },
            authToken = obj.optString("auth_token").takeIf { it.isNotBlank() },
        )
    }

    /**
     * `JSONObject.optDouble` returns NaN for a missing key, which forces every
     * caller to test for it; this returns null instead so `?.let` works.
     */
    private fun JSONObject.optNumber(key: String): Number? =
        if (!has(key) || isNull(key)) null else opt(key) as? Number
}

/** Carries a ready-to-emit [ApiError] for a request we refuse to run. */
class RequestFormatException(val error: ApiError) : Exception(error.message)
