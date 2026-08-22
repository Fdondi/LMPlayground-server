package com.druk.lmplayground.api

import com.druk.llamacpp.InferenceLimits
import com.druk.lmplayground.api.json.RequestFormatException
import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.ChatMessage
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.Role

/**
 * Maps an OpenAI `messages[]` array onto what the engine actually accepts.
 *
 * The engine's replay surface is deliberately narrow — `replayHistory` takes
 * two parallel arrays of user and assistant strings in strict alternation, and
 * `HistoryReplay.pairTurns` drops anything that isn't a complete
 * User→Assistant pair. There is no `tool` role on the wire. So this class has
 * to normalise a much richer OpenAI conversation down to that shape:
 *
 * - `system` / `developer` messages (from anywhere in the array) are
 *   concatenated into the session's system prompt.
 * - Consecutive same-role messages are merged — OpenAI permits them, strict
 *   alternation does not.
 * - A leading assistant message is dropped, matching `pairTurns`.
 * - `tool` messages are folded into the following user turn (see
 *   [flattenToolResults]).
 * - The **trailing** user turn is not replayed; it becomes the `addMessage`
 *   content for this generation.
 *
 * Pure: no Android or engine dependencies, so the whole normalisation is a
 * plain JVM unit test.
 */
object ApiHistoryMapper {

    /** Marker the response carries when a tool round trip had to be flattened. */
    const val WARNING_TOOL_HISTORY_FLATTENED = "tool_history_flattened"

    /** Marker for images attached to anything but the final user turn. */
    const val WARNING_HISTORY_IMAGES_DROPPED = "history_images_dropped"

    data class MappedHistory(
        /** Concatenated system/developer messages; "" when there were none. */
        val systemPrompt: String,
        /** Parallel arrays for `LlamaGenerationSession.replayHistory`. */
        val replayUser: List<String>,
        val replayAssistant: List<String>,
        /** The turn to generate from — passed to `addMessage`. */
        val finalUserContent: String,
        /** `data:` or `lmp-blob:` URL from the final user turn, if any. */
        val finalImageUrl: String?,
        /** True when the conversation carried a tool round trip. */
        val hasToolHistory: Boolean,
        val warnings: List<String>,
    )

    /**
     * @throws RequestFormatException when the conversation cannot be run at all
     *         (no user turn to answer, oversized payload, multiple images in
     *         one turn). The carried [ApiError] is emitted verbatim.
     */
    fun map(messages: List<ChatMessage>): MappedHistory {
        val warnings = mutableListOf<String>()

        // 1. System prompt: pull system/developer messages out wherever they
        //    sit. createSession takes exactly one system prompt string.
        val systemPrompt = messages
            .filter { it.role == Role.SYSTEM }
            .map { it.textContent() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        // 2. Normalise the rest into a User/Assistant alternation, folding tool
        //    results forward as we go.
        val conversation = messages.filter { it.role != Role.SYSTEM }
        val hasToolHistory = conversation.any {
            it.role == Role.TOOL || it.toolCalls.isNotEmpty()
        }
        if (hasToolHistory) warnings += WARNING_TOOL_HISTORY_FLATTENED

        val turns = normalise(conversation)

        if (turns.isEmpty() || turns.last().role != Role.USER) {
            throw RequestFormatException(
                ApiError(
                    message = "The conversation must end with a user or tool message — " +
                        "there is nothing for the model to answer.",
                    type = ErrorType.INVALID_REQUEST,
                    param = "messages",
                )
            )
        }

        // 3. The trailing user turn drives this generation; everything before
        //    it is replayed to rebuild the KV cache.
        val finalTurn = turns.last()
        val history = turns.dropLast(1)

        val replayUser = mutableListOf<String>()
        val replayAssistant = mutableListOf<String>()
        var index = 0
        while (index < history.size) {
            val user = history[index]
            val assistant = history.getOrNull(index + 1)
            if (user.role == Role.USER && assistant?.role == Role.ASSISTANT) {
                replayUser += user.text
                replayAssistant += assistant.text
                index += 2
            } else {
                // An unpaired turn (e.g. a trailing user turn with no answer).
                // Skip it, matching HistoryReplay.pairTurns rather than
                // inventing a pair — only complete turns feed the KV cache.
                index += 1
            }
        }

        // 4. Images. Only the final turn's image can be staged: setImageData
        //    applies to the next addMessage, and replayHistory is text-only.
        val lastUserIndex = messages.indexOfLast { it.role == Role.USER }
        val historyImages = messages
            .filterIndexed { i, _ -> i != lastUserIndex }
            .sumOf { it.images().size }
        if (historyImages > 0) warnings += WARNING_HISTORY_IMAGES_DROPPED

        val finalImages = messages.getOrNull(lastUserIndex)?.images().orEmpty()
        if (finalImages.size > 1) {
            throw RequestFormatException(
                ApiError(
                    message = "Only one image per turn is supported; this message has " +
                        "${finalImages.size}.",
                    type = ErrorType.INVALID_REQUEST,
                    param = "messages",
                )
            )
        }

        // 5. Every string is about to cross the binder individually — fail
        //    before we mutate any session state.
        validate(systemPrompt, "system prompt")
        replayUser.forEachIndexed { i, text -> validate(text, "messages[user #$i]") }
        replayAssistant.forEachIndexed { i, text -> validate(text, "messages[assistant #$i]") }
        validate(finalTurn.text, "final user message")

        return MappedHistory(
            systemPrompt = systemPrompt,
            replayUser = replayUser,
            replayAssistant = replayAssistant,
            finalUserContent = finalTurn.text,
            finalImageUrl = finalImages.firstOrNull()?.url,
            hasToolHistory = hasToolHistory,
            warnings = warnings,
        )
    }

    private data class Turn(val role: Role, val text: String)

    /**
     * Collapse an OpenAI conversation into strictly alternating turns.
     *
     * Tool results are buffered and prefixed onto the next user turn (or become
     * one, if the conversation ends on tool output — the normal shape of a
     * tool-result re-send).
     */
    private fun normalise(conversation: List<ChatMessage>): List<Turn> {
        val turns = mutableListOf<Turn>()
        val pendingToolResults = mutableListOf<String>()

        fun flushPendingInto(text: String): String {
            if (pendingToolResults.isEmpty()) return text
            val prefix = pendingToolResults.joinToString("\n")
            pendingToolResults.clear()
            return if (text.isBlank()) prefix else "$prefix\n\n$text"
        }

        fun append(role: Role, text: String) {
            val last = turns.lastOrNull()
            if (last != null && last.role == role) {
                // Merge — replayHistory cannot express two consecutive turns
                // with the same role.
                turns[turns.lastIndex] = last.copy(
                    text = if (last.text.isBlank()) text else "${last.text}\n\n$text"
                )
            } else {
                turns += Turn(role, text)
            }
        }

        for (message in conversation) {
            when (message.role) {
                Role.TOOL -> pendingToolResults += renderToolResult(message)
                Role.USER -> append(Role.USER, flushPendingInto(message.textContent()))
                Role.ASSISTANT -> {
                    // Tool results must never land *before* the assistant turn
                    // that requested them; if they're still pending here the
                    // conversation is malformed, so emit them as a user turn.
                    if (pendingToolResults.isNotEmpty()) {
                        append(Role.USER, flushPendingInto(""))
                    }
                    append(Role.ASSISTANT, renderAssistant(message))
                }
                Role.SYSTEM -> Unit // handled by the caller
            }
        }
        // Trailing tool results are the whole point of a tool-result re-send:
        // they become the turn the model answers.
        if (pendingToolResults.isNotEmpty()) {
            append(Role.USER, flushPendingInto(""))
        }

        // A leading assistant turn has no user turn to pair with.
        if (turns.firstOrNull()?.role == Role.ASSISTANT) turns.removeAt(0)
        return turns
    }

    /**
     * Render an assistant turn that carried tool calls back into plain text.
     *
     * Lossy by construction — the model's own template has a dedicated
     * representation for tool calls that we cannot reconstruct through
     * `replayHistory`. This is the fallback used when the parked session that
     * *would* have preserved the exact KV state is gone (expired, evicted, or
     * the app restarted). See [ParkedToolTurns] for the lossless path.
     */
    private fun renderAssistant(message: ChatMessage): String {
        val body = message.content.orEmpty()
        if (message.toolCalls.isEmpty()) return body
        val calls = message.toolCalls.joinToString("\n") { call ->
            "[tool_call ${call.name}(${call.arguments})]"
        }
        return if (body.isBlank()) calls else "$body\n$calls"
    }

    private fun renderToolResult(message: ChatMessage): String {
        val id = message.toolCallId ?: "unknown"
        return "<tool_result id=\"$id\">${message.textContent()}</tool_result>"
    }

    private fun validate(text: String, field: String) {
        // ApiLimits.byteCost mirrors InferenceLimits' UTF-16 arithmetic
        // (Parcel.writeString), but the engine's constant is authoritative for
        // what the AIDL proxy will actually accept.
        if (ApiLimits.byteCost(text) > InferenceLimits.MAX_PAYLOAD_BYTES) {
            throw RequestFormatException(
                ApiError(
                    message = "$field is ${text.length * 2 / 1024} KB; the per-message limit " +
                        "is ${InferenceLimits.MAX_PAYLOAD_BYTES / 1024} KB.",
                    type = ErrorType.PAYLOAD_TOO_LARGE,
                    param = field,
                )
            )
        }
    }
}
