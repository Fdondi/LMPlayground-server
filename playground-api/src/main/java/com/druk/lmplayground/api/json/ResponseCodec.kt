package com.druk.lmplayground.api.json

import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.CandidateModel
import com.druk.lmplayground.api.model.ChatCompletion
import com.druk.lmplayground.api.model.ChatCompletionChunk
import com.druk.lmplayground.api.model.ChatMessage
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.LmpCompletionInfo
import com.druk.lmplayground.api.model.ModelCapabilities
import com.druk.lmplayground.api.model.ModelEntry
import com.druk.lmplayground.api.model.ModelList
import com.druk.lmplayground.api.model.Role
import com.druk.lmplayground.api.model.ServiceInfo
import com.druk.lmplayground.api.model.ToolCall
import com.druk.lmplayground.api.model.Usage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes and parses everything flowing back from LM Playground: streamed
 * chunks, terminal completions, the models list, service info and errors.
 *
 * The chunk and completion objects are byte-for-byte OpenAI-shaped, so a
 * client that already parses `chat.completion.chunk` from an SSE stream can
 * feed these strings to its existing parser unchanged.
 */
object ResponseCodec {

    private const val OBJECT_CHUNK = "chat.completion.chunk"
    private const val OBJECT_COMPLETION = "chat.completion"

    // ── Streaming chunks ─────────────────────────────────────────────────

    fun encodeChunk(chunk: ChatCompletionChunk): String = JSONObject().apply {
        put("id", chunk.id)
        put("object", OBJECT_CHUNK)
        put("created", System.currentTimeMillis() / 1000)
        put("model", chunk.model)
        put("choices", JSONArray().put(JSONObject().apply {
            put("index", 0)
            put("delta", JSONObject().apply {
                chunk.contentDelta?.let { put("content", it) }
                // Thinking goes to `reasoning_content`, the de-facto standard
                // used by DeepSeek / vLLM / Ollama, so clients can collapse it
                // without string-matching <think> tags.
                chunk.reasoningDelta?.let { put("reasoning_content", it) }
                if (chunk.contentDelta == null && chunk.reasoningDelta == null) {
                    put("role", Role.ASSISTANT.wire)
                }
            })
            put("finish_reason", chunk.finishReason ?: JSONObject.NULL)
        }))
    }.toString()

    fun decodeChunk(json: String): ChatCompletionChunk {
        val root = JSONObject(json)
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta")
        return ChatCompletionChunk(
            id = root.optString("id"),
            model = root.optString("model"),
            contentDelta = delta?.optString("content")?.takeIf { it.isNotEmpty() },
            reasoningDelta = delta?.optString("reasoning_content")?.takeIf { it.isNotEmpty() },
            finishReason = choice?.optString("finish_reason")?.takeIf { it.isNotBlank() },
        )
    }

    // ── Terminal completion ──────────────────────────────────────────────

    fun encodeCompletion(completion: ChatCompletion): String = JSONObject().apply {
        put("id", completion.id)
        put("object", OBJECT_COMPLETION)
        put("created", completion.created)
        put("model", completion.model)
        put("choices", JSONArray().put(JSONObject().apply {
            put("index", 0)
            put("message", JSONObject().apply {
                put("role", completion.message.role.wire)
                // Null content (not "") when the turn produced only tool
                // calls — that is what the OpenAI schema specifies and what
                // clients round-trip back to us in the next request.
                put("content", completion.message.content ?: JSONObject.NULL)
                completion.message.reasoningContent?.let { put("reasoning_content", it) }
                if (completion.message.toolCalls.isNotEmpty()) {
                    put("tool_calls", JSONArray().apply {
                        completion.message.toolCalls.forEach { call ->
                            put(JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put("function", JSONObject()
                                    .put("name", call.name)
                                    .put("arguments", call.arguments)))
                        }
                    })
                }
            })
            put("finish_reason", completion.finishReason)
        }))
        put("usage", JSONObject().apply {
            // Always 0: the engine does not expose a prompt token count over
            // AIDL. Reporting 0 and documenting it beats inventing a number.
            put("prompt_tokens", completion.usage.promptTokens)
            put("completion_tokens", completion.usage.completionTokens)
            put("total_tokens", completion.usage.totalTokens)
        })
        put("lmp", JSONObject().apply {
            put("reasoning_tokens", completion.lmp.reasoningTokens)
            put("duration_ms", completion.lmp.durationMs)
            put("model_was_preloaded", completion.lmp.modelWasPreloaded)
            put("headless_load_ms", completion.lmp.headlessLoadMs)
            completion.lmp.continuationToken?.let { put("continuation_token", it) }
            if (completion.lmp.warnings.isNotEmpty()) {
                put("warnings", JSONArray().apply { completion.lmp.warnings.forEach { put(it) } })
            }
        })
    }.toString()

    fun decodeCompletion(json: String): ChatCompletion {
        val root = JSONObject(json)
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val messageObj = choice?.optJSONObject("message")
        val usageObj = root.optJSONObject("usage")
        val lmpObj = root.optJSONObject("lmp")

        val toolCalls = messageObj?.optJSONArray("tool_calls")?.let { array ->
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

        return ChatCompletion(
            id = root.optString("id"),
            model = root.optString("model"),
            created = root.optLong("created"),
            message = ChatMessage(
                role = Role.ASSISTANT,
                content = messageObj?.opt("content")
                    ?.takeIf { it != JSONObject.NULL }?.toString(),
                toolCalls = toolCalls,
                reasoningContent = messageObj?.optString("reasoning_content")
                    ?.takeIf { it.isNotBlank() },
            ),
            finishReason = choice?.optString("finish_reason").orEmpty(),
            usage = Usage(
                promptTokens = usageObj?.optInt("prompt_tokens") ?: 0,
                completionTokens = usageObj?.optInt("completion_tokens") ?: 0,
                totalTokens = usageObj?.optInt("total_tokens") ?: 0,
            ),
            lmp = LmpCompletionInfo(
                reasoningTokens = lmpObj?.optInt("reasoning_tokens") ?: 0,
                durationMs = lmpObj?.optLong("duration_ms") ?: 0,
                modelWasPreloaded = lmpObj?.optBoolean("model_was_preloaded") ?: false,
                headlessLoadMs = lmpObj?.optLong("headless_load_ms") ?: 0,
                continuationToken = lmpObj?.optString("continuation_token")
                    ?.takeIf { it.isNotBlank() },
                warnings = lmpObj?.optJSONArray("warnings")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }
                }.orEmpty(),
            ),
        )
    }

    // ── Models list ──────────────────────────────────────────────────────

    fun encodeModelList(list: ModelList): String = JSONObject().apply {
        put("object", "list")
        put("data", JSONArray().apply {
            list.models.forEach { model ->
                put(JSONObject().apply {
                    put("id", model.id)
                    put("object", "model")
                    put("created", model.created)
                    put("owned_by", "lm-playground")
                    put("lmp", JSONObject().apply {
                        put("display_name", model.displayName)
                        put("downloaded", model.downloaded)
                        put("loaded", model.loaded)
                        put("custom", model.custom)
                        put("size_bytes", model.sizeBytes)
                        put("languages", JSONArray().apply {
                            model.languages.forEach { put(it) }
                        })
                        put("capabilities", JSONObject().apply {
                            put("vision", model.capabilities.vision)
                            put("tools", model.capabilities.tools)
                            put("thinking", model.capabilities.thinking)
                            put("verified", model.capabilities.verified)
                            put("max_context",
                                model.capabilities.maxContext ?: JSONObject.NULL)
                        })
                    })
                })
            }
        })
        put("lmp", JSONObject().apply {
            put("api_version", list.apiVersion)
            put("loaded_model", list.loadedModel ?: JSONObject.NULL)
            put("engine_busy", list.engineBusy)
            put("storage_configured", list.storageConfigured)
        })
    }.toString()

    fun decodeModelList(json: String): ModelList {
        val root = JSONObject(json)
        val data = root.optJSONArray("data") ?: JSONArray()
        val lmpObj = root.optJSONObject("lmp")
        return ModelList(
            models = (0 until data.length()).mapNotNull { i ->
                val entry = data.optJSONObject(i) ?: return@mapNotNull null
                val lmp = entry.optJSONObject("lmp")
                val caps = lmp?.optJSONObject("capabilities")
                ModelEntry(
                    id = entry.optString("id"),
                    displayName = lmp?.optString("display_name").orEmpty(),
                    downloaded = lmp?.optBoolean("downloaded") ?: false,
                    loaded = lmp?.optBoolean("loaded") ?: false,
                    custom = lmp?.optBoolean("custom") ?: false,
                    sizeBytes = lmp?.optLong("size_bytes") ?: 0,
                    languages = lmp?.optJSONArray("languages")?.let { array ->
                        (0 until array.length()).map { array.optString(it) }
                    }.orEmpty(),
                    capabilities = ModelCapabilities(
                        vision = caps?.optBoolean("vision") ?: false,
                        tools = caps?.optBoolean("tools") ?: false,
                        thinking = caps?.optBoolean("thinking") ?: false,
                        verified = caps?.optBoolean("verified") ?: false,
                        maxContext = caps?.opt("max_context")
                            ?.takeIf { it != JSONObject.NULL } as? Int,
                    ),
                    created = entry.optLong("created"),
                )
            },
            apiVersion = lmpObj?.optInt("api_version") ?: 0,
            loadedModel = lmpObj?.opt("loaded_model")
                ?.takeIf { it != JSONObject.NULL }?.toString(),
            engineBusy = lmpObj?.optBoolean("engine_busy") ?: false,
            storageConfigured = lmpObj?.optBoolean("storage_configured") ?: false,
        )
    }

    // ── Service info ─────────────────────────────────────────────────────

    fun encodeServiceInfo(info: ServiceInfo): String = JSONObject().apply {
        put("api_version", info.apiVersion)
        put("app_version_name", info.appVersionName)
        put("features", JSONArray().apply { info.features.forEach { put(it) } })
        put("limits", JSONObject().apply {
            put("max_request_bytes", info.maxRequestBytes)
            put("max_blob_bytes", info.maxBlobBytes)
        })
    }.toString()

    fun decodeServiceInfo(json: String): ServiceInfo {
        val root = JSONObject(json)
        val limits = root.optJSONObject("limits")
        val features = root.optJSONArray("features")
        return ServiceInfo(
            apiVersion = root.optInt("api_version"),
            appVersionName = root.optString("app_version_name"),
            features = buildSet {
                if (features != null) {
                    for (i in 0 until features.length()) add(features.optString(i))
                }
            },
            maxRequestBytes = limits?.optInt("max_request_bytes") ?: 0,
            maxBlobBytes = limits?.optLong("max_blob_bytes") ?: 0,
        )
    }
}

/**
 * The OpenAI error envelope, plus an `lmp` block carrying the HTTP status this
 * would have had over HTTP (so a future loopback server is a pure mapping) and
 * the context a client needs to recover — candidate models, the loaded model,
 * partial output, retry timing.
 */
object ErrorCodec {

    fun encode(error: ApiError): String = JSONObject().apply {
        put("error", JSONObject().apply {
            put("message", error.message)
            put("type", error.type)
            put("param", error.param ?: JSONObject.NULL)
            put("code", error.code ?: "lmp_${error.type}")
            put("lmp", JSONObject().apply {
                put("http_status", error.httpStatus)
                error.loadedModelId?.let { put("loaded_model", it) }
                if (error.candidates.isNotEmpty()) {
                    put("candidates", JSONArray().apply {
                        error.candidates.forEach { candidate ->
                            put(JSONObject()
                                .put("id", candidate.id)
                                .put("display_name", candidate.displayName)
                                .put("downloaded", candidate.downloaded))
                        }
                    })
                }
                put("partial_content", error.partialContent ?: JSONObject.NULL)
                put("retry_after_ms", error.retryAfterMs ?: JSONObject.NULL)
            })
        })
    }.toString()

    fun decode(json: String): ApiError {
        val root = try {
            JSONObject(json).optJSONObject("error")
        } catch (_: Throwable) {
            null
        } ?: return ApiError(
            message = "Malformed error envelope from LM Playground: $json",
            type = ErrorType.INTERNAL,
        )

        val lmp = root.optJSONObject("lmp")
        val type = root.optString("type", ErrorType.INTERNAL)
        return ApiError(
            message = root.optString("message"),
            type = type,
            param = root.optString("param").takeIf { it.isNotBlank() && it != "null" },
            code = root.optString("code").takeIf { it.isNotBlank() },
            httpStatus = lmp?.optInt("http_status") ?: ErrorType.httpStatus(type),
            candidates = lmp?.optJSONArray("candidates")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    val candidate = array.optJSONObject(i) ?: return@mapNotNull null
                    CandidateModel(
                        id = candidate.optString("id"),
                        displayName = candidate.optString("display_name"),
                        downloaded = candidate.optBoolean("downloaded"),
                    )
                }
            }.orEmpty(),
            loadedModelId = lmp?.opt("loaded_model")
                ?.takeIf { it != JSONObject.NULL }?.toString(),
            partialContent = lmp?.opt("partial_content")
                ?.takeIf { it != JSONObject.NULL }?.toString(),
            retryAfterMs = (lmp?.opt("retry_after_ms")
                ?.takeIf { it != JSONObject.NULL } as? Number)?.toLong(),
        )
    }
}
