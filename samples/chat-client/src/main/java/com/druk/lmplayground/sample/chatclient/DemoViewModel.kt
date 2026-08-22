package com.druk.lmplayground.sample.chatclient

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.api.ChatEvent
import com.druk.lmplayground.api.LmPlaygroundClient
import com.druk.lmplayground.api.model.ChatCompletion
import com.druk.lmplayground.api.model.ChatCompletionRequest
import com.druk.lmplayground.api.model.ChatMessage
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.LmpRequestOptions
import com.druk.lmplayground.api.model.Requirements
import com.druk.lmplayground.api.model.Role
import com.druk.lmplayground.api.model.ToolCall
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything an app integrating LM Playground actually has to write.
 *
 * The interesting parts are:
 * - [connect] and its degradation handling,
 * - [send], which is the same request shape you'd POST to an OpenAI-compatible
 *   server, and
 * - [runToolRound], the client-side tool loop.
 */
class DemoViewModel(app: Application) : AndroidViewModel(app) {

    private val client = LmPlaygroundClient(app)

    private val _uiState = MutableStateFlow(DemoUiState())
    val uiState: StateFlow<DemoUiState> = _uiState.asStateFlow()

    /** The conversation in wire format — this is what gets re-sent every turn. */
    private val conversation = mutableListOf<ChatMessage>()

    private val messageIds = AtomicLong(0)
    private var generationJob: Job? = null

    init {
        connect()
        observeConnection()
    }

    fun connect() {
        viewModelScope.launch {
            _uiState.update { it.copy(availability = Availability.Checking) }
            val info = client.connect()
            _uiState.update {
                it.copy(
                    availability = when {
                        info != null -> Availability.Ready(info.appVersionName)
                        else -> when (val state = client.state.value) {
                            is LmPlaygroundClient.State.Unavailable -> when (state.reason) {
                                LmPlaygroundClient.State.Reason.NOT_INSTALLED ->
                                    Availability.NotInstalled
                                LmPlaygroundClient.State.Reason.VERSION_TOO_OLD ->
                                    Availability.TooOld
                                LmPlaygroundClient.State.Reason.BIND_REFUSED ->
                                    Availability.BindRefused
                            }
                            else -> Availability.Disconnected
                        }
                    }
                )
            }
            if (info != null) refreshModels()
        }
    }

    /**
     * React to the process on the other side dying.
     *
     * This is not optional in a real integration: LM Playground can be killed
     * for memory at any time, and when it is, the model the user had loaded is
     * gone with it.
     */
    private fun observeConnection() {
        viewModelScope.launch {
            client.state.collect { state ->
                if (state is LmPlaygroundClient.State.Disconnected) {
                    _uiState.update {
                        it.copy(availability = Availability.Disconnected, isGenerating = false)
                    }
                    // Re-bind: Android recreates the service on the next bind,
                    // so this normally recovers within a second.
                    delay(RECONNECT_DELAY_MS)
                    connect()
                }
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            val list = runCatching { client.listModels() }.getOrNull() ?: return@launch
            _uiState.update {
                it.copy(models = list.models, loadedModel = list.loadedModel)
            }
        }
    }

    fun pinModel(filename: String?) {
        _uiState.update { it.copy(pinnedModel = filename) }
    }

    fun setToolsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(toolsEnabled = enabled) }
    }

    fun setRequireVision(required: Boolean) {
        _uiState.update { it.copy(requireVision = required) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun stop() {
        generationJob?.cancel()
    }

    fun send(text: String) {
        if (text.isBlank() || !_uiState.value.canSend) return

        conversation += ChatMessage(Role.USER, text)
        val userMessage = UiMessage(messageIds.incrementAndGet(), isUser = true, content = text)
        val assistantId = messageIds.incrementAndGet()
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage +
                    UiMessage(assistantId, isUser = false, content = "", streaming = true),
                isGenerating = true,
                error = null,
                // Nothing loaded means the first token waits on a multi-second
                // headless load; say so rather than looking hung.
                loadingModel = it.loadedModel == null,
            )
        }

        generationJob = viewModelScope.launch {
            runTurn(assistantId, continuationToken = null, toolRound = 0)
            _uiState.update { it.copy(isGenerating = false, loadingModel = false) }
        }
    }

    /**
     * One request/response cycle, recursing through client-side tool rounds.
     *
     * Bounded at [MAX_TOOL_ROUNDS] so a model that keeps asking for tools can't
     * loop forever — the same cap LM Playground applies to its own tools.
     */
    private suspend fun runTurn(assistantId: Long, continuationToken: String?, toolRound: Int) {
        val state = _uiState.value
        val request = ChatCompletionRequest(
            messages = conversation.toList(),
            model = state.pinnedModel,
            stream = true,
            tools = if (state.toolsEnabled) LocalTools.definitions else emptyList(),
            lmp = LmpRequestOptions(
                require = Requirements(
                    vision = state.requireVision,
                    tools = state.toolsEnabled,
                ),
                clientLabel = "LMP API Demo",
                continuationToken = continuationToken,
            ),
        )

        var completion: ChatCompletion? = null

        client.chatCompletion(request).collect { event ->
            when (event) {
                is ChatEvent.Delta -> {
                    _uiState.update { current ->
                        current.copy(
                            loadingModel = false,
                            messages = current.messages.map { message ->
                                if (message.id != assistantId) message
                                else message.copy(
                                    content = message.content + (event.content ?: ""),
                                    reasoning = message.reasoning + (event.reasoning ?: ""),
                                )
                            },
                        )
                    }
                }

                is ChatEvent.Done -> {
                    completion = event.completion
                    conversation += event.completion.message
                    finishMessage(assistantId, event.completion)
                }

                is ChatEvent.ToolCalls -> {
                    completion = event.completion
                    conversation += event.completion.message
                }

                is ChatEvent.Failed -> {
                    _uiState.update { it.copy(error = event.error, loadingModel = false) }
                    // Keep whatever streamed before the failure rather than
                    // silently dropping it.
                    event.error.partialContent?.let { partial ->
                        appendToMessage(assistantId, partial)
                    }
                    dropEmptyAssistantMessage(assistantId)
                }
            }
        }

        val result = completion ?: return
        if (result.finishReason == "tool_calls" && toolRound < MAX_TOOL_ROUNDS) {
            runToolRound(assistantId, result, toolRound)
        }
    }

    /**
     * Execute the model's tool calls locally and continue the conversation.
     *
     * Passing `continuation_token` back is what lets LM Playground resume from
     * the live KV cache using the model's own tool-response template. It is an
     * optimization, not a requirement — if the token has expired the request
     * still succeeds by replaying the conversation, just with a warning.
     */
    private suspend fun runToolRound(
        assistantId: Long,
        completion: ChatCompletion,
        toolRound: Int,
    ) {
        val executed = completion.message.toolCalls.map { call ->
            val result = LocalTools.execute(call)
            Log.i(TAG, "ran ${call.name}(${call.arguments}) -> $result")
            conversation += ChatMessage(
                role = Role.TOOL,
                content = result,
                toolCallId = call.id,
            )
            UiToolCall(call.name, call.arguments, result)
        }

        _uiState.update { current ->
            current.copy(messages = current.messages.map { message ->
                if (message.id != assistantId) message
                else message.copy(toolCalls = message.toolCalls + executed)
            })
        }

        runTurn(
            assistantId = assistantId,
            continuationToken = completion.lmp.continuationToken,
            toolRound = toolRound + 1,
        )
    }

    private fun finishMessage(assistantId: Long, completion: ChatCompletion) {
        _uiState.update { current ->
            current.copy(messages = current.messages.map { message ->
                if (message.id != assistantId) message
                else message.copy(
                    // The terminal completion carries the authoritative text —
                    // streamed deltas can lag it slightly by design.
                    content = completion.message.content ?: message.content,
                    reasoning = completion.message.reasoningContent ?: message.reasoning,
                    streaming = false,
                )
            })
        }
    }

    private fun appendToMessage(assistantId: Long, text: String) {
        _uiState.update { current ->
            current.copy(messages = current.messages.map { message ->
                if (message.id != assistantId) message
                else message.copy(content = message.content + text, streaming = false)
            })
        }
    }

    private fun dropEmptyAssistantMessage(assistantId: Long) {
        _uiState.update { current ->
            current.copy(messages = current.messages.filterNot {
                it.id == assistantId && it.content.isBlank() && it.reasoning.isBlank()
            })
        }
    }

    /** True when the error is worth an automatic retry after a short wait. */
    fun retryDelayMs(): Long? = _uiState.value.error
        ?.takeIf { it.type == ErrorType.ENGINE_BUSY }
        ?.retryAfterMs

    override fun onCleared() {
        super.onCleared()
        client.disconnect()
    }

    private companion object {
        private const val TAG = "DemoViewModel"
        const val MAX_TOOL_ROUNDS = 5
        const val RECONNECT_DELAY_MS = 1_000L
    }
}
