package com.druk.lmplayground

import android.util.Log
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.jni.NativeLlamaCpp
import com.druk.llamacpp.jni.NativeLlamaModel
import com.druk.llamacpp.jni.NativeLlamaSession
import com.druk.lmplayground.tools.ToolRegistry
import com.druk.lmplayground.tools.WebSearchTool
import com.druk.lmplayground.tools.JavaScriptTool
import com.druk.lmplayground.tools.WebFetchTool
import org.json.JSONArray
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

/**
 * Instrumented tests for tool/function calling support.
 *
 * Tests the full pipeline: tool definition -> model generation -> tool call detection
 * -> tool execution -> result submission -> final response.
 *
 * Setup: copy a tool-capable model to /data/local/tmp/:
 *   adb shell "cp /sdcard/Models/Qwen3-0.6B-Q4_K_M.gguf /data/local/tmp/ && chmod 666 /data/local/tmp/Qwen3-0.6B-Q4_K_M.gguf"
 */
@RunWith(AndroidJUnit4::class)
class ToolCallingTest {

    companion object {
        private const val TAG = "ToolCallingTest"

        private val CANDIDATE_MODELS = listOf(
            "gemma-4-E2B-it-Q4_K_M.gguf",
            "gemma-4-E4B-it-Q4_K_M.gguf",
            "Qwen3-0.6B-Q4_K_M.gguf",
            "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
            "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf"
        )
        private const val MODELS_PATH = "/data/local/tmp"
    }

    private lateinit var llamaCpp: NativeLlamaCpp
    private var llamaModel: NativeLlamaModel? = null
    private var session: NativeLlamaSession? = null

    @Before
    fun setUp() {
        llamaCpp = NativeLlamaCpp()
        llamaCpp.init(
            InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationInfo.nativeLibraryDir
        )
    }

    @After
    fun tearDown() {
        session?.destroy()
        session = null
        llamaModel?.unloadModel()
        llamaModel = null
    }

    private fun findModel(): File? {
        for (name in CANDIDATE_MODELS) {
            val file = File(MODELS_PATH, name)
            if (file.exists() && file.canRead()) return file
        }
        return null
    }

    private fun findToolCapableModel(): Pair<File, NativeLlamaModel>? {
        for (name in CANDIDATE_MODELS) {
            val file = File(MODELS_PATH, name)
            if (!file.exists() || !file.canRead()) continue
            Log.d(TAG, "Probing model: ${file.name}")
            val model = llamaCpp.loadModel(
                file.absolutePath,
                object : LlamaProgressCallback {
                    override fun onProgress(progress: Float) {}
                },
                disableRepack = false,
            )
            if (model == null) {
                Log.d(TAG, "  -> load returned null, skipping")
                continue
            }
            if (model.supportsToolCalling()) {
                Log.d(TAG, "  -> supports tool calling!")
                return Pair(file, model)
            }
            Log.d(TAG, "  -> does NOT support tool calling")
            model.unloadModel()
        }
        return null
    }

    private fun loadModel(modelFile: File): NativeLlamaModel {
        Log.d(TAG, "Loading model: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")
        val model = llamaCpp.loadModel(
            modelFile.absolutePath,
            object : LlamaProgressCallback {
                override fun onProgress(progress: Float) {
                    Log.d(TAG, "Loading: ${(progress * 100).toInt()}%")
                }
            },
            disableRepack = false,
        ) ?: error("loadModel returned null for ${modelFile.absolutePath}")
        llamaModel = model
        return model
    }

    /**
     * Generate response, returning the final text and the generate() return code.
     * Return code: 1 = normal completion, 2 = tool calls detected.
     */
    private fun generateResponse(
        session: NativeLlamaSession,
        maxTokens: Int = 2048,
        timeoutMs: Long = 120_000
    ): Pair<String, Int> {
        var lastResponse = ""
        var tokenCount = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        val callback = object : LlamaGenerationCallback {
            override fun onFullResponse(response: String) {
                lastResponse = response
            }
        }
        var result: Int
        while (true) {
            result = session.generate(callback)
            if (result != 0) break
            tokenCount++
            if (tokenCount >= maxTokens) {
                Log.d(TAG, "Reached max token limit ($maxTokens)")
                result = 1
                break
            }
            if (System.currentTimeMillis() > deadline) {
                Log.d(TAG, "Reached timeout after $tokenCount tokens")
                result = 1
                break
            }
        }
        Log.d(TAG, "Generation finished: $tokenCount tokens, result=$result")
        return Pair(lastResponse, result)
    }

    // -- Kotlin-only tool tests (no model needed) --

    @Test
    fun testToolRegistryJsonFormat() {
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        val json = registry.toOpenAIToolsJson()
        Log.d(TAG, "Tools JSON:\n$json")

        val arr = JSONArray(json)
        assertTrue("Should have at least 2 tools", arr.length() >= 2)

        // Validate each tool has correct structure
        for (i in 0 until arr.length()) {
            val tool = arr.getJSONObject(i)
            assertEquals("type should be function", "function", tool.getString("type"))
            val func = tool.getJSONObject("function")
            assertTrue("Should have name", func.has("name"))
            assertTrue("Should have description", func.has("description"))
            assertTrue("Should have parameters", func.has("parameters"))
        }
    }

    @Test
    fun testToolRegistryExecuteToolCalls() {
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        // 42 * 37 = 1554
        val toolCallsJson = """[{"id":"call_0","name":"run_javascript","arguments":"{\"code\":\"42 * 37\"}"}]"""
        val results = registry.executeToolCalls(toolCallsJson)
        Log.d(TAG, "Execute result: $results")

        val arr = JSONArray(results)
        assertEquals("Should have 1 result", 1, arr.length())
        val result = arr.getJSONObject(0)
        assertEquals("call_0", result.getString("id"))
        assertEquals("run_javascript", result.getString("name"))
        val content = org.json.JSONObject(result.getString("content"))
        assertEquals("1554", content.getString("result"))
    }

    @Test
    fun testToolRegistryUnknownTool() {
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        val toolCallsJson = """[{"id":"call_0","name":"nonexistent","arguments":"{}"}]"""
        val results = registry.executeToolCalls(toolCallsJson)
        Log.d(TAG, "Unknown tool result: $results")

        val arr = JSONArray(results)
        val content = arr.getJSONObject(0).getString("content")
        assertTrue("Should report error", content.contains("error"))
    }

    // -- Model capability tests --

    @Test
    fun testSupportsToolCallingDetection() {
        val modelFile = findModel()
        assumeTrue(
            "No model file found in $MODELS_PATH. Copy a model first.",
            modelFile != null
        )
        val model = loadModel(modelFile!!)
        val supports = model.supportsToolCalling()
        Log.d(TAG, "Model ${modelFile.name} supportsToolCalling: $supports")
        // Log result — this is informational, not a hard assertion,
        // since small models may or may not support tools
    }

    @Test
    fun testSetToolsOnSession() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue(
            "Model ${modelFile.name} does not support tool calling. " +
            "Try: Qwen2.5-3B-Instruct, Qwen3-4B, or Mistral-Nemo",
            model.supportsToolCalling()
        )

        val session = model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())
        // No crash = success. Tools are accepted by the native layer.
        Log.d(TAG, "setTools completed without error")
    }

    // -- Full tool calling integration tests --

    @Test
    fun testGenerateWithToolsSet() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue(
            "Model ${modelFile.name} does not support tool calling",
            model.supportsToolCalling()
        )

        val session = model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())
        session.addMessage("What is 123 * 456? Use the run_javascript tool.", false)

        val (response, result) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
        Log.d(TAG, "Response (tools set): result=$result\n$response")

        // The model either made a tool call (result=2) or responded directly (result=1)
        assertTrue("Generate should return 1 or 2", result == 1 || result == 2)

        if (result == 2) {
            val toolCallsJson = session.getToolCallsJson()
            Log.d(TAG, "Tool calls detected: $toolCallsJson")
            val arr = JSONArray(toolCallsJson)
            assertTrue("Should have at least one tool call", arr.length() > 0)

            val call = arr.getJSONObject(0)
            assertTrue("Tool call should have name", call.has("name"))
            assertTrue("Tool call should have arguments", call.has("arguments"))
            assertTrue("Tool call should have id", call.has("id"))
            Log.d(TAG, "Tool call: name=${call.getString("name")}, args=${call.getString("arguments")}")
        } else {
            Log.d(TAG, "Model did not make tool call (answered directly). This is expected for small models.")
        }
    }

    @Test
    fun testFullToolCallCycle() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue(
            "Model ${modelFile.name} does not support tool calling",
            model.supportsToolCalling()
        )

        val session = model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())

        // Use a very direct prompt to maximize chances of tool use
        session.addMessage("Use the run_javascript tool to tell me what day of the week it is.", false)

        val (response1, result1) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
        Log.d(TAG, "First generation: result=$result1\n$response1")

        if (result1 == 2) {
            // Tool calls detected - execute the full cycle
            val toolCallsJson = session.getToolCallsJson()
            Log.d(TAG, "Tool calls: $toolCallsJson")

            // Execute tools
            val toolResults = registry.executeToolCalls(toolCallsJson)
            Log.d(TAG, "Tool results: $toolResults")

            // Submit results
            val submitResult = session.submitToolResults(toolResults, false)
            assertEquals("submitToolResults should succeed", 0, submitResult)

            // Generate final response
            val (response2, result2) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
            Log.d(TAG, "Final response: result=$result2\n$response2")
            assertTrue("Final response should not be empty", response2.isNotBlank())
            // The response should mention the day of the week
            Log.d(TAG, "Full tool call cycle completed successfully!")
        } else {
            Log.d(TAG, "Model did not use tools - answered directly. Small models may not reliably call tools.")
        }
    }

    @Test
    fun testJavaScriptToolCallCycle() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue(
            "Model ${modelFile.name} does not support tool calling",
            model.supportsToolCalling()
        )

        val session = model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())

        session.addMessage("Use the run_javascript tool to compute 7823 * 4519", false)

        val (response1, result1) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
        Log.d(TAG, "First generation: result=$result1\n$response1")

        if (result1 == 2) {
            val toolCallsJson = session.getToolCallsJson()
            Log.d(TAG, "Tool calls: $toolCallsJson")

            val arr = JSONArray(toolCallsJson)
            val call = arr.getJSONObject(0)
            assertEquals("Should call run_javascript", "run_javascript", call.getString("name"))

            val toolResults = registry.executeToolCalls(toolCallsJson)
            Log.d(TAG, "Tool results: $toolResults")

            val submitResult = session.submitToolResults(toolResults, false)
            assertEquals("submitToolResults should succeed", 0, submitResult)

            val (response2, result2) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
            Log.d(TAG, "Final response: result=$result2\n$response2")
            assertTrue("Final response should not be empty", response2.isNotBlank())

            // The correct answer is 35,352,137
            Log.d(TAG, "JavaScript tool call cycle completed!")
        } else {
            Log.d(TAG, "Model did not use run_javascript tool.")
        }
    }

    @Test
    fun testGenerateWithoutToolsStillWorks() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        // Don't set tools - should work as before
        session.addMessage("Say hello in one word", false)
        val (response, result) = generateResponse(session, maxTokens = 64)
        Log.d(TAG, "No-tools response: result=$result\n$response")
        assertTrue("Response should not be empty", response.isNotBlank())
        assertEquals("Should complete normally (not tool call)", 1, result)
    }

    @Test
    fun testSetEmptyToolsDisablesTools() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        // Set tools then clear them
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())
        session.setTools("[]")

        session.addMessage("Say hello in one word", false)
        val (response, result) = generateResponse(session, maxTokens = 64)
        Log.d(TAG, "Cleared-tools response: result=$result\n$response")
        assertTrue("Response should not be empty", response.isNotBlank())
        assertEquals("Should complete normally after clearing tools", 1, result)
    }

    @Test
    fun testToolCallCycleWithThinkingEnabled() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue(
            "Model ${modelFile.name} does not support tool calling",
            model.supportsToolCalling()
        )

        val session = model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())

        // KEY DIFFERENCE: thinking=true (matches app behavior)
        session.addMessage("Use the run_javascript tool to compute 15 times 37", true)

        val (response1, result1) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
        Log.d(TAG, "THINKING-ENABLED: First gen: result=$result1, response='$response1'")

        if (result1 == 2) {
            val toolCallsJson = session.getToolCallsJson()
            Log.d(TAG, "THINKING-ENABLED: Tool calls: $toolCallsJson")

            val toolResults = registry.executeToolCalls(toolCallsJson)
            Log.d(TAG, "THINKING-ENABLED: Tool results: $toolResults")

            // Submit with thinking=true (matches app behavior)
            val submitResult = session.submitToolResults(toolResults, true)
            assertEquals("submitToolResults should succeed", 0, submitResult)

            val (response2, result2) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
            Log.d(TAG, "THINKING-ENABLED: Final gen: result=$result2, response='$response2'")
            assertTrue(
                "Final response should not be empty after tool call with thinking enabled",
                response2.isNotBlank()
            )
        } else {
            Log.d(TAG, "THINKING-ENABLED: Model did not use tools")
        }
    }

    @Test
    fun testToolCallThinkingDisabledJavaScript() {
        // Test thinking=false with run_javascript (the other test used thinking=true)
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue("Needs tool calling", model.supportsToolCalling())

        val session = model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())

        session.addMessage("Use the run_javascript tool to compute 7823 * 4519", false)
        val (r1, res1) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
        Log.d(TAG, "JS-NOTHINK: First gen result=$res1, response='${r1.take(100)}'")

        if (res1 == 2) {
            val tc = session.getToolCallsJson()
            val tr = registry.executeToolCalls(tc)
            Log.d(TAG, "JS-NOTHINK: Tool calls=$tc, results=$tr")
            session.submitToolResults(tr, false)
            val (r2, res2) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
            Log.d(TAG, "JS-NOTHINK: Final gen result=$res2, response='$r2', length=${r2.length}")
        }
    }

    @Test
    fun testReproduceAppBehavior() {
        // Reproduce exact app behavior: user asks "what's current date?" with Gemma 4.
        // The fix: always enable thinking for the response phase after tool results,
        // as Gemma 4 generates empty responses without thinking mode.
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue("Needs tool calling", model.supportsToolCalling())

        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())

        // Initial message with thinking disabled (user toggle off)
        session.addMessage("what's current date?", false)

        val (response1, result1) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
        Log.d(TAG, "APP-REPRO: First gen result=$result1")

        if (result1 == 2) {
            val toolCallsJson = session.getToolCallsJson()
            Log.d(TAG, "APP-REPRO: Tool calls: $toolCallsJson")
            val toolResults = registry.executeToolCalls(toolCallsJson)
            Log.d(TAG, "APP-REPRO: Tool results: $toolResults")

            // FIX: enable thinking for the response phase if model supports it
            val thinkingForResponse = model.supportsThinking()
            val submitResult = session.submitToolResults(toolResults, thinkingForResponse)
            Log.d(TAG, "APP-REPRO: submitToolResults returned $submitResult (thinking=$thinkingForResponse)")

            val (response2, result2) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
            Log.d(TAG, "APP-REPRO: Final gen result=$result2")
            Log.d(TAG, "APP-REPRO: Final gen response='$response2'")
            assertTrue("Final response should not be empty", response2.isNotBlank())
        } else {
            Log.d(TAG, "APP-REPRO: No tool call, direct response")
        }
    }

    // -- Web tool tests (no model needed) --

    @Test
    fun testWebSearchReturnsResults() {
        val tool = WebSearchTool()
        val result = tool.execute("""{"query":"OpenAI"}""")
        Log.d(TAG, "WebSearch result: ${result.take(500)}")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error", json.has("error"))
        val results = json.getJSONArray("results")
        assertTrue("Should have at least 1 result", results.length() > 0)
        val first = results.getJSONObject(0)
        assertTrue("Result should have title", first.has("title"))
        assertTrue("Result should have url", first.has("url"))
        assertTrue("URL should start with http", first.getString("url").startsWith("http"))
    }

    @Test
    fun testWebSearchMaxResults() {
        val tool = WebSearchTool()
        val result = tool.execute("""{"query":"Android development","max_results":2}""")
        Log.d(TAG, "WebSearch max_results result: ${result.take(500)}")
        val json = org.json.JSONObject(result)
        if (!json.has("error")) {
            val results = json.getJSONArray("results")
            assertTrue("Should have at most 2 results", results.length() <= 2)
        }
    }

    @Test
    fun testWebFetchReturnsContent() {
        val tool = WebFetchTool()
        val result = tool.execute("""{"url":"https://httpbin.org/html"}""")
        Log.d(TAG, "WebFetch result: ${result.take(500)}")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        assertTrue("Should have content", json.has("content"))
        val content = json.getString("content")
        assertTrue("Content should not be empty", content.isNotEmpty())
    }

    @Test
    fun testWebFetchTruncation() {
        val tool = WebFetchTool()
        val result = tool.execute("""{"url":"https://httpbin.org/html","max_length":50}""")
        Log.d(TAG, "WebFetch truncated: ${result.take(200)}")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        val content = json.getString("content")
        assertTrue("Content should be truncated to ~50 chars, got ${content.length}", content.length <= 54)
    }

    @Test
    fun testWebFetchInvalidUrl() {
        val tool = WebFetchTool()
        val result = tool.execute("""{"url":"https://thisdoesnotexist.invalid"}""")
        Log.d(TAG, "WebFetch invalid: $result")
        val json = org.json.JSONObject(result)
        assertTrue("Should have error for invalid URL", json.has("error"))
    }

    @Test
    fun testToolRegistryIncludesWebTools() {
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        val json = registry.toOpenAIToolsJson()
        assertTrue("Should include web_search", json.contains("web_search"))
        assertTrue("Should include web_fetch", json.contains("web_fetch"))
        assertTrue("Should include run_javascript", json.contains("run_javascript"))
    }

    // -- JavaScript tool tests --

    @Test
    fun testJavaScriptBasicMath() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = JavaScriptTool(ctx)
        val result = tool.execute("""{"code":"2 + 2"}""")
        Log.d(TAG, "JS basic math: $result")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        assertEquals("4", json.getString("result"))
    }

    @Test
    fun testJavaScriptStringResult() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = JavaScriptTool(ctx)
        val result = tool.execute("""{"code":"'hello ' + 'world'"}""")
        Log.d(TAG, "JS string: $result")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        assertEquals("hello world", json.getString("result"))
    }

    @Test
    fun testJavaScriptComplexCode() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = JavaScriptTool(ctx)
        val code = "const fib = (n) => { const a = [0,1]; for(let i=2;i<n;i++) a.push(a[i-1]+a[i-2]); return a; }; JSON.stringify(fib(10))"
        val result = tool.execute("""{"code":${org.json.JSONObject.quote(code)}}""")
        Log.d(TAG, "JS complex: $result")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        assertTrue("Should contain fibonacci", json.getString("result").contains("[0,1,1,2,3,5,8,13,21,34]"))
    }

    @Test
    fun testJavaScriptSyntaxError() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = JavaScriptTool(ctx)
        val result = tool.execute("""{"code":"function("}""")
        Log.d(TAG, "JS syntax error: $result")
        val json = org.json.JSONObject(result)
        assertTrue("Should have error for syntax error", json.has("error"))
    }

    // -- Diagnostic test: reports all model capabilities --

    @Test
    fun testReportAllModelCapabilities() {
        Log.d(TAG, "=== Model Capability Report ===")
        for (name in CANDIDATE_MODELS) {
            val file = File(MODELS_PATH, name)
            if (!file.exists() || !file.canRead()) {
                Log.d(TAG, "$name: NOT FOUND on device")
                continue
            }
            val model = llamaCpp.loadModel(
                file.absolutePath,
                object : LlamaProgressCallback {
                    override fun onProgress(progress: Float) {}
                },
                disableRepack = false,
            )
            if (model == null) {
                Log.d(TAG, "$name: load returned null (corrupt or unsupported)")
                continue
            }
            val thinking = model.supportsThinking()
            val tools = model.supportsToolCalling()
            Log.d(TAG, "$name: thinking=$thinking, tools=$tools")
            model.unloadModel()
        }
        Log.d(TAG, "=== End Report ===")
        Log.d(TAG, "Recommended models for tool calling: Qwen2.5-3B-Instruct, Qwen3-4B, Mistral-Nemo-7B")
    }
}
