package com.druk.lmplayground.sample.chatclient

import com.druk.lmplayground.api.model.ToolCall
import com.druk.lmplayground.api.model.ToolDefinition
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Tools that run **in this app's process**.
 *
 * This is the strongest evidence that the API is genuinely public: LM
 * Playground never sees this code, never executes it, and its own built-in
 * tools (web search, page fetch, JavaScript) are not exposed to us. The
 * contract is purely the OpenAI tool-calling round trip — we declare schemas,
 * we get `tool_calls` back, we run them ourselves, we send the results.
 */
object LocalTools {

    val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "get_current_time",
            description = "Get the current local date and time on the user's device.",
            parametersSchema = """{"type":"object","properties":{},"required":[]}""",
        ),
        ToolDefinition(
            name = "roll_dice",
            description = "Roll one or more dice and return the individual results.",
            parametersSchema = """
                {"type":"object",
                 "properties":{
                   "sides":{"type":"integer","description":"Faces per die. Defaults to 6."},
                   "count":{"type":"integer","description":"How many dice. Defaults to 1."}
                 },
                 "required":[]}
            """.trimIndent(),
        ),
    )

    /**
     * Execute one call and return the string to send back as the `tool` message
     * content.
     *
     * Never throws: a tool that fails should tell the model it failed, so the
     * model can recover in-conversation. Throwing here would fail the whole
     * turn instead.
     */
    fun execute(call: ToolCall): String = try {
        val args = runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject())
        when (call.name) {
            "get_current_time" -> SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault())
                .format(Date())

            "roll_dice" -> {
                val sides = args.optInt("sides", 6).coerceIn(2, 1000)
                val count = args.optInt("count", 1).coerceIn(1, 100)
                val rolls = List(count) { Random.nextInt(1, sides + 1) }
                """{"rolls":${rolls},"total":${rolls.sum()},"sides":$sides}"""
            }

            else -> """{"error":"This app does not implement a tool called '${call.name}'."}"""
        }
    } catch (t: Throwable) {
        """{"error":"${t.message?.replace('"', '\'')}"}"""
    }
}
