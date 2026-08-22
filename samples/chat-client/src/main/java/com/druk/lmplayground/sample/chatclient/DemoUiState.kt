package com.druk.lmplayground.sample.chatclient

import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.ModelEntry

/**
 * A chat message as this app models it.
 *
 * Note that it is *not* the API's `ChatMessage`: the UI wants a stable id for
 * list keys, and separate reasoning/content buffers it can append to as tokens
 * stream. The conversion to the wire format happens in [DemoViewModel], which
 * is exactly where a real integration would put it.
 */
data class UiMessage(
    val id: Long,
    val isUser: Boolean,
    val content: String,
    val reasoning: String = "",
    /** True while tokens are still arriving into this message. */
    val streaming: Boolean = false,
    /** Tool calls this turn produced, rendered as chips. */
    val toolCalls: List<UiToolCall> = emptyList(),
)

data class UiToolCall(
    val name: String,
    val arguments: String,
    val result: String,
)

/**
 * Every way the connection can be degraded, as a closed set.
 *
 * Modelling these explicitly rather than as a nullable error string is the
 * point of the sample: each one needs a *different* recovery affordance, and a
 * real integration has to handle all of them.
 */
sealed interface Availability {
    object Checking : Availability

    data class Ready(val appVersion: String) : Availability

    /** LM Playground isn't installed. Offer the Play Store. */
    object NotInstalled : Availability

    /** Installed, but older than the API version this client needs. */
    object TooOld : Availability

    /** Was connected, then the service or its process went away. */
    object Disconnected : Availability

    /** The bind itself was refused. */
    object BindRefused : Availability
}

data class DemoUiState(
    val availability: Availability = Availability.Checking,
    val messages: List<UiMessage> = emptyList(),
    val isGenerating: Boolean = false,
    /** Populated by the model sheet. */
    val models: List<ModelEntry> = emptyList(),
    val loadedModel: String? = null,
    /** Null for "auto"; otherwise the GGUF filename the user pinned. */
    val pinnedModel: String? = null,
    val toolsEnabled: Boolean = false,
    val requireVision: Boolean = false,
    /** The last error, shown as a dismissible banner with a recovery action. */
    val error: ApiError? = null,
    /** Set while a headless model load is likely to be delaying first token. */
    val loadingModel: Boolean = false,
) {
    val canSend: Boolean
        get() = !isGenerating && availability is Availability.Ready
}
