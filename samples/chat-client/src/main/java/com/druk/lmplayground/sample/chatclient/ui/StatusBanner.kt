package com.druk.lmplayground.sample.chatclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.sample.chatclient.Availability

/**
 * Surfaces every degraded state with the recovery action that actually applies.
 *
 * This is deliberately exhaustive rather than a generic "something went wrong":
 * each of these needs a genuinely different response from the user, and getting
 * them right is most of the work of integrating against an API that shares a
 * scarce resource with another app's UI.
 */
@Composable
fun StatusBanner(
    availability: Availability,
    error: ApiError?,
    onOpenPlayStore: () -> Unit,
    onOpenLmPlayground: () -> Unit,
    onRetryConnect: () -> Unit,
    onRetryAuto: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val banner = resolveBanner(availability, error) ?: return

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (banner.severe) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(banner.title, style = MaterialTheme.typography.titleSmall)
            Text(
                banner.body,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (banner.candidates.isNotEmpty()) {
                Text(
                    "Models that would work: ${banner.candidates.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (error != null) {
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }
                banner.action?.let { (label, kind) ->
                    TextButton(onClick = {
                        when (kind) {
                            ActionKind.PLAY_STORE -> onOpenPlayStore()
                            ActionKind.OPEN_APP -> onOpenLmPlayground()
                            ActionKind.RECONNECT -> onRetryConnect()
                            ActionKind.RETRY -> onRetryAuto()
                        }
                    }) { Text(label) }
                }
            }
        }
    }
}

private enum class ActionKind { PLAY_STORE, OPEN_APP, RECONNECT, RETRY }

private data class Banner(
    val title: String,
    val body: String,
    val severe: Boolean,
    val action: Pair<String, ActionKind>? = null,
    val candidates: List<String> = emptyList(),
)

private fun resolveBanner(availability: Availability, error: ApiError?): Banner? {
    // Connection problems outrank request errors — retrying a request against a
    // service you aren't bound to is pointless.
    when (availability) {
        Availability.NotInstalled -> return Banner(
            title = "LM Playground is not installed",
            body = "This demo runs its model inside LM Playground. Install it, download a " +
                "model, and come back.",
            severe = true,
            action = "Get it on Play" to ActionKind.PLAY_STORE,
        )
        Availability.TooOld -> return Banner(
            title = "LM Playground is out of date",
            body = "The installed version doesn't expose the inference API this demo needs.",
            severe = true,
            action = "Update" to ActionKind.PLAY_STORE,
        )
        Availability.Disconnected -> return Banner(
            title = "Disconnected",
            body = "LM Playground's process went away — reconnecting. Any model it had " +
                "loaded is gone, so the next request may need to load one.",
            severe = false,
            action = "Reconnect now" to ActionKind.RECONNECT,
        )
        Availability.BindRefused -> return Banner(
            title = "Could not connect",
            body = "LM Playground refused the connection.",
            severe = true,
            action = "Retry" to ActionKind.RECONNECT,
        )
        Availability.Checking, is Availability.Ready -> Unit
    }

    if (error == null) return null

    return when (error.type) {
        ErrorType.PERMISSION_DENIED -> Banner(
            title = "Access denied",
            body = error.message,
            severe = true,
            action = "Open LM Playground" to ActionKind.OPEN_APP,
        )
        ErrorType.NO_MODEL_AVAILABLE, ErrorType.NO_MODEL_LOADED, ErrorType.MODEL_NOT_FOUND ->
            Banner(
                title = "No usable model",
                body = error.message,
                severe = false,
                action = "Open LM Playground" to ActionKind.OPEN_APP,
                candidates = error.candidates.map { it.displayName },
            )
        ErrorType.CAPABILITY_UNAVAILABLE -> Banner(
            title = "The loaded model can't do that",
            body = error.message,
            severe = false,
            action = "Open LM Playground" to ActionKind.OPEN_APP,
            candidates = error.candidates.map { it.displayName },
        )
        ErrorType.MODEL_MISMATCH -> Banner(
            title = "A different model is loaded",
            body = error.message,
            severe = false,
            action = "Use whatever is loaded" to ActionKind.RETRY,
        )
        ErrorType.ENGINE_BUSY -> Banner(
            title = "Engine busy",
            body = error.message,
            severe = false,
            action = "Retry" to ActionKind.RETRY,
        )
        ErrorType.ENGINE_UNAVAILABLE -> Banner(
            title = "Engine unavailable",
            body = error.message,
            severe = true,
            action = "Retry" to ActionKind.RETRY,
        )
        ErrorType.CANCELLED -> null // The user did this on purpose.
        else -> Banner(
            title = "Request failed",
            body = "${error.message} (${error.type}, HTTP ${error.httpStatus})",
            severe = true,
        )
    }
}
