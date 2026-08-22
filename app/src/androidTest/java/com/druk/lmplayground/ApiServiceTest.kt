package com.druk.lmplayground

import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import com.druk.lmplayground.api.ApiService
import com.druk.lmplayground.api.IChatCompletionCallback
import com.druk.lmplayground.api.IChatService
import com.druk.lmplayground.api.LmPlaygroundApi
import com.druk.lmplayground.api.json.ErrorCodec
import com.druk.lmplayground.api.json.RequestCodec
import com.druk.lmplayground.api.json.ResponseCodec
import com.druk.lmplayground.api.model.ChatCompletionRequest
import com.druk.lmplayground.api.model.ChatMessage
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.LmpRequestOptions
import com.druk.lmplayground.api.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end exercise of the exported API surface, with no GGUF required.
 *
 * Everything here runs against a real bound service and real binder
 * transactions — the parts a unit test cannot cover: the AIDL marshalling, the
 * service's own wiring, and the paths that fail *before* any model is needed.
 */
@RunWith(AndroidJUnit4::class)
class ApiServiceTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private lateinit var service: IChatService

    @Before
    fun bind() {
        val context = ApplicationProvider.getApplicationContext<App>()
        val binder = serviceRule.bindService(
            Intent(LmPlaygroundApi.ACTION_BIND).setComponent(
                ComponentName(context, ApiService::class.java)
            )
        )
        service = IChatService.Stub.asInterface(binder)
    }

    @Test
    fun reportsItsApiVersion() {
        // Transaction 0 — the one call a client may make without
        // feature-detecting first.
        assertEquals(LmPlaygroundApi.API_VERSION, service.apiVersion)
    }

    @Test
    fun serviceInfoAdvertisesTheShippedFeatures() {
        val info = ResponseCodec.decodeServiceInfo(service.serviceInfo)
        assertEquals(LmPlaygroundApi.API_VERSION, info.apiVersion)
        assertTrue(info.appVersionName.isNotBlank())
        listOf(
            LmPlaygroundApi.FEATURE_CHAT_STREAM,
            LmPlaygroundApi.FEATURE_CHAT_TOOLS,
            LmPlaygroundApi.FEATURE_CHAT_VISION,
            LmPlaygroundApi.FEATURE_MODELS_LIST,
            LmPlaygroundApi.FEATURE_BLOBS,
        ).forEach { assertTrue("missing feature $it", info.supports(it)) }
        assertEquals(700 * 1024, info.maxRequestBytes)
    }

    @Test
    fun listModelsReturnsAWellFormedEnvelope() {
        // No SAF folder is configured in an instrumented run, so the list is
        // empty — but the envelope still has to parse and carry its metadata.
        val list = ResponseCodec.decodeModelList(service.listModels())
        assertEquals(LmPlaygroundApi.API_VERSION, list.apiVersion)
        assertNotNull(list.models)
    }

    @Test
    fun requestWithNoModelAndNoLoadAllowedFailsCleanly() {
        val request = ChatCompletionRequest(
            messages = listOf(ChatMessage(Role.USER, "hello")),
            lmp = LmpRequestOptions(allowLoad = false),
        )
        val result = runRequest(RequestCodec.encode(request))

        val error = result.error ?: error("expected an error, got ${result.completion}")
        // Either is correct depending on whether storage happens to be
        // configured on the test device; both are the honest refusal rather
        // than a hang or a crash.
        assertTrue(
            "unexpected type ${error.type}",
            error.type == ErrorType.NO_MODEL_LOADED || error.type == ErrorType.NO_MODEL_AVAILABLE,
        )
        assertEquals(503, error.httpStatus)
    }

    @Test
    fun malformedRequestIsRejectedSynchronously() {
        val result = runRequest("{ this is not json")
        val error = result.error ?: error("expected an error")
        assertEquals(ErrorType.INVALID_REQUEST, error.type)
        // Rejected before an id could be assigned, and the callback still
        // fired — clients only need one error path.
        assertEquals("", result.requestId)
    }

    @Test
    fun unsupportedFieldIsRejectedRatherThanIgnored() {
        val result = runRequest(
            """{"messages":[{"role":"user","content":"hi"}],"response_format":{"type":"json"}}"""
        )
        val error = result.error ?: error("expected an error")
        assertEquals(ErrorType.INVALID_REQUEST, error.type)
        assertEquals("response_format", error.param)
    }

    @Test
    fun putBlobAcceptsASmallImageAndRejectsAnOversizedOne() {
        // A 1x1 PNG, small enough to be nothing but header — enough to prove
        // the FD crosses and the decode path runs.
        val png = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            android.util.Base64.DEFAULT,
        )
        val handle = putBlob(png, png.size.toLong())
        assertTrue("expected an lmp-blob handle, got '$handle'",
            handle.startsWith(LmPlaygroundApi.BLOB_URL_PREFIX))

        // A lying declared size is refused before we read a byte.
        assertEquals("", putBlob(png, 30L * 1024 * 1024))
    }

    @Test
    fun cancellingAnUnknownRequestIsANoOp() {
        service.cancel("chatcmpl-lmp-nonexistent")
        // Still alive and serving.
        assertEquals(LmPlaygroundApi.API_VERSION, service.apiVersion)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private class Result(
        val requestId: String,
        val chunks: List<String>,
        val completion: String?,
        val error: com.druk.lmplayground.api.model.ApiError?,
    )

    private fun runRequest(requestJson: String, timeoutSeconds: Long = 20): Result {
        val latch = CountDownLatch(1)
        val chunks = mutableListOf<String>()
        var completion: String? = null
        var error: com.druk.lmplayground.api.model.ApiError? = null

        val callback = object : IChatCompletionCallback.Stub() {
            override fun onChunk(requestId: String, chunkJson: String) {
                synchronized(chunks) { chunks += chunkJson }
            }

            override fun onComplete(requestId: String, completionJson: String) {
                completion = completionJson
                latch.countDown()
            }

            override fun onError(requestId: String, errorJson: String) {
                error = ErrorCodec.decode(errorJson)
                latch.countDown()
            }
        }

        val requestId = service.createChatCompletion(requestJson, callback)
        assertTrue(
            "no terminal callback within ${timeoutSeconds}s",
            latch.await(timeoutSeconds, TimeUnit.SECONDS),
        )
        return Result(requestId, chunks, completion, error)
    }

    private fun putBlob(bytes: ByteArray, declaredSize: Long): String {
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread({
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) }
        }, "test-blob-writer").start()
        return read.use { service.putBlob(it, "image/png", declaredSize) }
    }
}
