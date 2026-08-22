package com.druk.lmplayground.api

import com.druk.llamacpp.InferenceLimits
import com.druk.lmplayground.api.json.ErrorCodec
import com.druk.lmplayground.api.json.RequestCodec
import com.druk.lmplayground.api.json.RequestFormatException
import com.druk.lmplayground.api.json.ResponseCodec
import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.CandidateModel
import com.druk.lmplayground.api.model.ChatCompletion
import com.druk.lmplayground.api.model.ChatCompletionChunk
import com.druk.lmplayground.api.model.ChatCompletionRequest
import com.druk.lmplayground.api.model.ChatMessage
import com.druk.lmplayground.api.model.ContentPart
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.LmpRequestOptions
import com.druk.lmplayground.api.model.Requirements
import com.druk.lmplayground.api.model.Role
import com.druk.lmplayground.api.model.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The wire schema, exercised through the same codecs both processes use.
 *
 * Robolectric because these use platform `org.json`, which on a bare JVM
 * classpath is the android.jar stub that throws `RuntimeException("Stub!")`.
 */
@RunWith(RobolectricTestRunner::class)
class ApiCodecTest {

    // ── Requests ─────────────────────────────────────────────────────────

    @Test
    fun requestRoundTripsThroughJson() {
        val original = ChatCompletionRequest(
            messages = listOf(
                ChatMessage(Role.SYSTEM, "Be terse."),
                ChatMessage(Role.USER, "Hello"),
            ),
            model = "Qwen3-4B-Q4_K_M.gguf",
            stream = true,
            temperature = 0.7f,
            topK = 20,
            maxTokens = 256,
            stop = listOf("\n\nUser:"),
            lmp = LmpRequestOptions(
                require = Requirements(vision = true, minContext = 8192),
                allowLoad = false,
                clientLabel = "Test",
            ),
        )

        val decoded = RequestCodec.decode(RequestCodec.encode(original))

        assertEquals(original.model, decoded.model)
        assertEquals(2, decoded.messages.size)
        assertEquals("Be terse.", decoded.messages[0].content)
        assertEquals(0.7f, decoded.temperature!!, 0.001f)
        assertEquals(20, decoded.topK)
        assertEquals(256, decoded.maxTokens)
        assertEquals(listOf("\n\nUser:"), decoded.stop)
        assertTrue(decoded.lmp.require.vision)
        assertEquals(8192, decoded.lmp.require.minContext)
        assertTrue(!decoded.lmp.allowLoad)
        assertEquals("Test", decoded.lmp.clientLabel)
    }

    @Test
    fun modelAutoDecodesToNull() {
        val decoded = RequestCodec.decode(
            """{"model":"auto","messages":[{"role":"user","content":"hi"}]}"""
        )
        assertNull(decoded.model)
    }

    @Test
    fun multimodalContentPartsAreParsed() {
        val decoded = RequestCodec.decode("""
            {"messages":[{"role":"user","content":[
              {"type":"text","text":"what is this?"},
              {"type":"image_url","image_url":{"url":"lmp-blob:abc"}}
            ]}]}
        """.trimIndent())
        val message = decoded.messages.single()
        assertEquals("what is this?", message.textContent())
        assertEquals("lmp-blob:abc", message.images().single().url)
    }

    @Test
    fun unknownContentPartTypesAreSkippedNotFatal() {
        // Forward compatibility: OpenAI keeps adding part kinds (audio, file).
        val decoded = RequestCodec.decode("""
            {"messages":[{"role":"user","content":[
              {"type":"text","text":"hi"},
              {"type":"audio","audio":{"data":"..."}}
            ]}]}
        """.trimIndent())
        assertEquals("hi", decoded.messages.single().textContent())
    }

    @Test
    fun assistantToolCallsRoundTrip() {
        val original = ChatCompletionRequest(messages = listOf(
            ChatMessage(Role.USER, "time?"),
            ChatMessage(Role.ASSISTANT, null,
                toolCalls = listOf(ToolCall("call_0", "get_current_time", "{}"))),
            ChatMessage(Role.TOOL, "12:30", toolCallId = "call_0"),
        ))
        val decoded = RequestCodec.decode(RequestCodec.encode(original))

        val assistant = decoded.messages[1]
        assertNull(assistant.content)
        assertEquals("get_current_time", assistant.toolCalls.single().name)
        assertEquals("call_0", decoded.messages[2].toolCallId)
    }

    @Test
    fun developerRoleIsTreatedAsSystem() {
        val decoded = RequestCodec.decode(
            """{"messages":[{"role":"developer","content":"rules"},
                            {"role":"user","content":"hi"}]}"""
        )
        assertEquals(Role.SYSTEM, decoded.messages[0].role)
    }

    // ── Rejections: be honest rather than silently wrong ─────────────────

    @Test
    fun unsupportedFieldsAreRejectedNotIgnored() {
        listOf("response_format", "logprobs", "functions", "function_call").forEach { field ->
            val error = runCatching {
                RequestCodec.decode(
                    """{"messages":[{"role":"user","content":"hi"}],"$field":{"a":1}}"""
                )
            }.exceptionOrNull() as RequestFormatException
            assertEquals(ErrorType.INVALID_REQUEST, error.error.type)
            assertEquals(field, error.error.param)
        }
    }

    @Test
    fun multipleChoicesAreRejected() {
        val error = runCatching {
            RequestCodec.decode("""{"messages":[{"role":"user","content":"hi"}],"n":3}""")
        }.exceptionOrNull() as RequestFormatException
        assertEquals("n", error.error.param)
    }

    @Test
    fun emptyMessagesIsRejected() {
        val error = runCatching {
            RequestCodec.decode("""{"messages":[]}""")
        }.exceptionOrNull() as RequestFormatException
        assertEquals(ErrorType.INVALID_REQUEST, error.error.type)
    }

    @Test
    fun malformedJsonIsRejected() {
        val error = runCatching { RequestCodec.decode("{not json") }
            .exceptionOrNull() as RequestFormatException
        assertEquals(ErrorType.INVALID_REQUEST, error.error.type)
    }

    @Test
    fun oversizedRequestIsRejectedBeforeParsing() {
        val huge = """{"messages":[{"role":"user","content":"${"x".repeat(400_000)}"}]}"""
        val error = runCatching { RequestCodec.decode(huge) }
            .exceptionOrNull() as RequestFormatException
        assertEquals(ErrorType.PAYLOAD_TOO_LARGE, error.error.type)
        assertTrue(error.error.message.contains("putBlob"))
    }

    // ── Responses are byte-shape OpenAI ──────────────────────────────────

    @Test
    fun chunkIsOpenAiShaped() {
        val json = ResponseCodec.encodeChunk(
            ChatCompletionChunk(id = "chatcmpl-1", model = "m.gguf", contentDelta = " A tabby")
        )
        val root = JSONObject(json)
        assertEquals("chat.completion.chunk", root.getString("object"))
        val choice = root.getJSONArray("choices").getJSONObject(0)
        assertEquals(0, choice.getInt("index"))
        assertEquals(" A tabby", choice.getJSONObject("delta").getString("content"))
        assertTrue(choice.isNull("finish_reason"))
    }

    @Test
    fun reasoningUsesTheReasoningContentChannel() {
        val json = ResponseCodec.encodeChunk(
            ChatCompletionChunk(id = "x", model = "m", reasoningDelta = "hmm")
        )
        val delta = JSONObject(json).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("delta")
        assertEquals("hmm", delta.getString("reasoning_content"))
        assertTrue(!delta.has("content"))
    }

    @Test
    fun toolCallCompletionHasNullContentNotEmptyString() {
        // The OpenAI schema specifies null, and clients round-trip it back to
        // us — an empty string would change the replayed conversation.
        val json = ResponseCodec.encodeCompletion(ChatCompletion(
            id = "x", model = "m", created = 0,
            message = ChatMessage(Role.ASSISTANT, null,
                toolCalls = listOf(ToolCall("call_0", "f", "{}"))),
            finishReason = "tool_calls",
        ))
        val message = JSONObject(json).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message")
        assertTrue(message.isNull("content"))
        assertEquals("f", message.getJSONArray("tool_calls").getJSONObject(0)
            .getJSONObject("function").getString("name"))
    }

    @Test
    fun completionRoundTrips() {
        val original = ChatCompletion(
            id = "chatcmpl-lmp-1", model = "m.gguf", created = 1_700_000_000,
            message = ChatMessage(Role.ASSISTANT, "Hello", reasoningContent = "thinking"),
            finishReason = "stop",
            usage = com.druk.lmplayground.api.model.Usage(0, 12, 12),
        )
        val decoded = ResponseCodec.decodeCompletion(ResponseCodec.encodeCompletion(original))
        assertEquals("Hello", decoded.message.content)
        assertEquals("thinking", decoded.message.reasoningContent)
        assertEquals("stop", decoded.finishReason)
        assertEquals(12, decoded.usage.completionTokens)
    }

    // ── Errors ───────────────────────────────────────────────────────────

    @Test
    fun errorEnvelopeCarriesHttpStatusAndRecoveryContext() {
        val original = ApiError(
            message = "The loaded model does not support image input.",
            type = ErrorType.CAPABILITY_UNAVAILABLE,
            param = "lmp.require.vision",
            loadedModelId = "gemma.gguf",
            candidates = listOf(CandidateModel("qwen.gguf", "Qwen 3.5 2B", true)),
        )
        val decoded = ErrorCodec.decode(ErrorCodec.encode(original))

        assertEquals(ErrorType.CAPABILITY_UNAVAILABLE, decoded.type)
        assertEquals(409, decoded.httpStatus)
        assertEquals("gemma.gguf", decoded.loadedModelId)
        assertEquals("Qwen 3.5 2B", decoded.candidates.single().displayName)
        assertEquals("lmp.require.vision", decoded.param)
    }

    @Test
    fun everyErrorTypeMapsToItsDocumentedHttpStatus() {
        val expected = mapOf(
            ErrorType.INVALID_REQUEST to 400,
            ErrorType.PERMISSION_DENIED to 403,
            ErrorType.MODEL_NOT_FOUND to 404,
            ErrorType.CAPABILITY_UNAVAILABLE to 409,
            ErrorType.MODEL_MISMATCH to 409,
            ErrorType.PAYLOAD_TOO_LARGE to 413,
            ErrorType.CANCELLED to 499,
            ErrorType.NO_MODEL_AVAILABLE to 503,
            ErrorType.NO_MODEL_LOADED to 503,
            ErrorType.ENGINE_BUSY to 503,
            ErrorType.ENGINE_UNAVAILABLE to 503,
            ErrorType.INTERNAL to 500,
        )
        expected.forEach { (type, status) ->
            assertEquals("wrong status for $type", status, ErrorType.httpStatus(type))
        }
    }

    @Test
    fun malformedErrorEnvelopeDegradesToInternalError() {
        val decoded = ErrorCodec.decode("not json at all")
        assertEquals(ErrorType.INTERNAL, decoded.type)
    }

    @Test
    fun partialContentSurvivesTheRoundTrip() {
        val decoded = ErrorCodec.decode(ErrorCodec.encode(ApiError(
            message = "engine died",
            type = ErrorType.ENGINE_UNAVAILABLE,
            partialContent = "half an answ",
            retryAfterMs = 15_000,
        )))
        assertEquals("half an answ", decoded.partialContent)
        assertEquals(15_000L, decoded.retryAfterMs)
        assertTrue(decoded.isRetryable)
    }

    // ── Cross-module invariants ──────────────────────────────────────────

    /**
     * `:playground-api` cannot depend on `:app`, so [ApiLimits.MAX_REQUEST_BYTES]
     * duplicates the engine's own ceiling. If they ever drift, a client would
     * be told its request fits and then get a hard binder failure. Pin them.
     */
    @Test
    fun apiRequestBudgetMatchesTheEngineBudget() {
        assertEquals(InferenceLimits.MAX_PAYLOAD_BYTES, ApiLimits.MAX_REQUEST_BYTES)
    }

    @Test
    fun byteCostIsUtf16BecauseParcelWriteStringIs() {
        assertEquals(20, ApiLimits.byteCost("0123456789"))
    }
}
