package com.druk.lmplayground.conversation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.toMutableStateList

class ConversationUiState(
    initialMessages: List<Message>
) {
    private val _messages: MutableList<Message> = initialMessages.toMutableStateList()
    val messages: List<Message> = _messages

    fun addMessage(msg: Message) {
        _messages.add(msg) // Add to the end of the list
    }

    fun updateLastMessage(msg: String, thinkingTokens: Int = 0, responseTokens: Int = 0) {
        val message = _messages.last()
        val isThinkingActive = message.thinkingStartTimeMs > 0
        val thinkingJustEnded = isThinkingActive && msg.contains("</think>")

        val duration = if (isThinkingActive) {
            ((System.currentTimeMillis() - message.thinkingStartTimeMs) / 1000).toInt()
        } else {
            message.thinkingDurationSeconds
        }

        _messages[_messages.size - 1] = message.copy(
            content = msg,
            thinkingDurationSeconds = duration,
            thinkingStartTimeMs = if (thinkingJustEnded) 0L else message.thinkingStartTimeMs,
            thinkingTokens = thinkingTokens,
            responseTokens = responseTokens
        )
    }

    fun resetMessages() {
        _messages.clear()
    }
}

@Immutable
data class Message(
    val author: String,
    val content: String,
    val image: Int? = null,
    val thinkingDurationSeconds: Int = 0,
    val thinkingStartTimeMs: Long = 0,
    val thinkingTokens: Int = 0,
    val responseTokens: Int = 0
)
