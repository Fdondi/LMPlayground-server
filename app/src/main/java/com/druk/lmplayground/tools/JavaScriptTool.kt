package com.druk.lmplayground.tools

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class JavaScriptTool(private val context: Context) : Tool {
    override val name = "run_javascript"
    override val description = "Execute JavaScript code in a sandboxed V8 isolate and return the value of the last expression as a string. Use this for arithmetic and math (note: `**` is exponent, `^` is bitwise XOR — not power), current date and time via `new Date()` (e.g. `new Date().toString()` for local time with day-of-week and timezone, `new Date().toISOString()` for UTC), string manipulation, JSON, regex, and any deterministic computation. Standard ECMAScript only — no network, filesystem, or DOM access."
    override val parametersSchema = """{"type":"object","properties":{"code":{"type":"string","description":"JavaScript source. The value of the last expression is returned. Examples: '(2 + 3) * 4', '2 ** 10', 'new Date().toString()', 'JSON.stringify({pi: Math.PI})'."}},"required":["code"]}"""

    override fun execute(arguments: String): String {
        return try {
            if (!JavaScriptSandbox.isSupported()) {
                return """{"error":"JavaScript engine not supported on this device"}"""
            }
            val args = JSONObject(arguments)
            val code = args.getString("code")

            val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context)
                .get(5, TimeUnit.SECONDS)
            try {
                val startupParams = IsolateStartupParameters()
                if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                    startupParams.setMaxHeapSizeBytes(16L * 1024 * 1024)
                }
                val isolate = sandbox.createIsolate(startupParams)
                try {
                    // AndroidX JS engine returns empty string unless the code explicitly
                    // produces a String value. Wrap with String() to capture the result
                    // of the last expression, using eval() to handle multi-statement code.
                    val escaped = code.replace("\\", "\\\\").replace("`", "\\`")
                    val wrapped = "String(eval(`$escaped`))"
                    val result = isolate.evaluateJavaScriptAsync(wrapped)
                        .get(10, TimeUnit.SECONDS)
                    """{"result":${JSONObject.quote(result)}}"""
                } finally {
                    isolate.close()
                }
            } finally {
                sandbox.close()
            }
        } catch (e: Exception) {
            val message = (e.cause?.message ?: e.message ?: "Execution failed")
                .replace("\"", "'")
            """{"error":"$message"}"""
        }
    }
}
