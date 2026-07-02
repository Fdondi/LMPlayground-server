package com.druk.lmplayground.conversation

import org.json.JSONArray

/**
 * Maps the native layer's tool-call / tool-result JSON payloads into the
 * [ToolCallInfo] list shown on the assistant message.
 */
object ToolCallInfoMapper {

    fun buildToolCallInfoList(
        toolCallsJson: String,
        toolResultsJson: String,
        totalDurationMs: Long,
    ): List<ToolCallInfo> {
        val calls = JSONArray(toolCallsJson)
        val results = JSONArray(toolResultsJson)
        val resultMap = mutableMapOf<String, String>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            resultMap[r.getString("id")] = r.getString("content")
        }
        val count = calls.length().coerceAtLeast(1)
        val perCallMs = totalDurationMs / count
        return (0 until calls.length()).map { i ->
            val call = calls.getJSONObject(i)
            val id = call.getString("id")
            ToolCallInfo(
                name = call.getString("name"),
                arguments = call.getString("arguments"),
                result = resultMap[id] ?: "",
                durationMs = perCallMs
            )
        }
    }
}
