package com.druk.lmplayground.api

import com.druk.lmplayground.api.json.RequestFormatException
import com.druk.lmplayground.api.model.ChatMessage
import com.druk.lmplayground.api.model.ContentPart
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.Role
import com.druk.lmplayground.api.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Normalising a rich OpenAI conversation down to what the engine accepts:
 * a system prompt, strictly alternating user/assistant pairs, and one final
 * user turn to generate from.
 */
class ApiHistoryMapperTest {

    private fun user(text: String) = ChatMessage(Role.USER, text)
    private fun assistant(text: String) = ChatMessage(Role.ASSISTANT, text)
    private fun system(text: String) = ChatMessage(Role.SYSTEM, text)

    @Test
    fun splitsHistoryFromTheTurnToAnswer() {
        val mapped = ApiHistoryMapper.map(listOf(
            system("Be terse."),
            user("first"), assistant("one"),
            user("second"), assistant("two"),
            user("third"),
        ))

        assertEquals("Be terse.", mapped.systemPrompt)
        assertEquals(listOf("first", "second"), mapped.replayUser)
        assertEquals(listOf("one", "two"), mapped.replayAssistant)
        // The trailing user turn is NOT replayed — it becomes addMessage.
        assertEquals("third", mapped.finalUserContent)
    }

    @Test
    fun multipleSystemMessagesAreConcatenated() {
        val mapped = ApiHistoryMapper.map(listOf(
            system("Be terse."),
            user("hi"), assistant("hello"),
            system("Answer in French."),
            user("bonjour?"),
        ))
        assertEquals("Be terse.\n\nAnswer in French.", mapped.systemPrompt)
    }

    @Test
    fun consecutiveSameRoleMessagesAreMerged() {
        // OpenAI permits these; replayHistory's strict alternation does not.
        val mapped = ApiHistoryMapper.map(listOf(
            user("part one"), user("part two"),
            assistant("answer"),
            user("next"),
        ))
        assertEquals(listOf("part one\n\npart two"), mapped.replayUser)
        assertEquals(listOf("answer"), mapped.replayAssistant)
        assertEquals("next", mapped.finalUserContent)
    }

    @Test
    fun leadingAssistantMessageIsDropped() {
        val mapped = ApiHistoryMapper.map(listOf(
            assistant("How can I help?"),
            user("a question"),
        ))
        assertTrue(mapped.replayUser.isEmpty())
        assertEquals("a question", mapped.finalUserContent)
    }

    @Test
    fun conversationEndingOnAssistantIsRejected() {
        val error = runCatching {
            ApiHistoryMapper.map(listOf(user("hi"), assistant("hello")))
        }.exceptionOrNull() as RequestFormatException
        assertEquals(ErrorType.INVALID_REQUEST, error.error.type)
        assertEquals(400, error.error.httpStatus)
    }

    // ── Tool history flattening ──────────────────────────────────────────

    @Test
    fun toolRoundTripIsFlattenedIntoAlternatingTurns() {
        val mapped = ApiHistoryMapper.map(listOf(
            user("what time is it?"),
            ChatMessage(
                role = Role.ASSISTANT,
                content = null,
                toolCalls = listOf(ToolCall("call_0", "get_current_time", "{}")),
            ),
            ChatMessage(Role.TOOL, "12:30", toolCallId = "call_0"),
        ))

        assertTrue(mapped.hasToolHistory)
        assertTrue(ApiHistoryMapper.WARNING_TOOL_HISTORY_FLATTENED in mapped.warnings)
        // The assistant's tool call is rendered as text so it can be replayed.
        assertEquals(listOf("what time is it?"), mapped.replayUser)
        assertEquals(listOf("[tool_call get_current_time({})]"), mapped.replayAssistant)
        // Trailing tool results become the turn the model answers.
        assertTrue(mapped.finalUserContent.contains("12:30"))
        assertTrue(mapped.finalUserContent.contains("""id="call_0""""))
    }

    @Test
    fun toolResultsFoldIntoTheFollowingUserTurn() {
        val mapped = ApiHistoryMapper.map(listOf(
            user("roll a die"),
            ChatMessage(Role.ASSISTANT, null,
                toolCalls = listOf(ToolCall("call_0", "roll_dice", "{}"))),
            ChatMessage(Role.TOOL, "4", toolCallId = "call_0"),
            user("and again?"),
        ))
        // The tool result rides along with the *next* user turn rather than
        // becoming a bare turn of its own — strict alternation has no slot for
        // a standalone tool turn. Here that next turn is the final one, so the
        // result lands in the content we generate from.
        assertEquals(listOf("roll a die"), mapped.replayUser)
        assertEquals(listOf("[tool_call roll_dice({})]"), mapped.replayAssistant)
        assertTrue(mapped.finalUserContent.contains("4"))
        assertTrue(mapped.finalUserContent.endsWith("and again?"))
    }

    @Test
    fun toolResultsInMidConversationAreReplayedWithTheirUserTurn() {
        val mapped = ApiHistoryMapper.map(listOf(
            user("roll a die"),
            ChatMessage(Role.ASSISTANT, null,
                toolCalls = listOf(ToolCall("call_0", "roll_dice", "{}"))),
            ChatMessage(Role.TOOL, "4", toolCallId = "call_0"),
            user("thanks"),
            assistant("You rolled a 4."),
            user("one more?"),
        ))
        // Two complete pairs now, and the folded tool result is inside the
        // second replayed user turn.
        assertEquals(2, mapped.replayUser.size)
        assertTrue(mapped.replayUser[1].contains("4"))
        assertTrue(mapped.replayUser[1].endsWith("thanks"))
        assertEquals("one more?", mapped.finalUserContent)
    }

    @Test
    fun conversationWithoutToolsCarriesNoWarning() {
        val mapped = ApiHistoryMapper.map(listOf(user("hi")))
        assertTrue(mapped.warnings.isEmpty())
        assertTrue(!mapped.hasToolHistory)
    }

    // ── Images ───────────────────────────────────────────────────────────

    @Test
    fun finalTurnImageIsExtracted() {
        val mapped = ApiHistoryMapper.map(listOf(
            ChatMessage(Role.USER, parts = listOf(
                ContentPart.Text("what is this?"),
                ContentPart.ImageUrl("lmp-blob:abc"),
            )),
        ))
        assertEquals("lmp-blob:abc", mapped.finalImageUrl)
        assertEquals("what is this?", mapped.finalUserContent)
    }

    @Test
    fun historyImagesAreDroppedWithAWarning() {
        // replayHistory is text-only and setImageData applies to the next
        // addMessage, so earlier images cannot be reconstructed. Say so.
        val mapped = ApiHistoryMapper.map(listOf(
            ChatMessage(Role.USER, parts = listOf(
                ContentPart.Text("first"), ContentPart.ImageUrl("lmp-blob:old"),
            )),
            assistant("a cat"),
            user("and now?"),
        ))
        assertTrue(ApiHistoryMapper.WARNING_HISTORY_IMAGES_DROPPED in mapped.warnings)
        assertNull(mapped.finalImageUrl)
    }

    @Test
    fun twoImagesInOneTurnIsRejected() {
        val error = runCatching {
            ApiHistoryMapper.map(listOf(
                ChatMessage(Role.USER, parts = listOf(
                    ContentPart.ImageUrl("lmp-blob:a"),
                    ContentPart.ImageUrl("lmp-blob:b"),
                )),
            ))
        }.exceptionOrNull() as RequestFormatException
        assertEquals(ErrorType.INVALID_REQUEST, error.error.type)
    }

    // ── Payload budget ───────────────────────────────────────────────────

    @Test
    fun oversizedMessageIsRejectedBeforeAnySessionStateChanges() {
        // Every string here crosses the binder individually, so the check has
        // to happen before we touch the engine — not as a mid-replay failure.
        val huge = "x".repeat(400_000)
        val error = runCatching {
            ApiHistoryMapper.map(listOf(user(huge)))
        }.exceptionOrNull() as RequestFormatException
        assertEquals(ErrorType.PAYLOAD_TOO_LARGE, error.error.type)
        assertEquals(413, error.error.httpStatus)
    }
}
