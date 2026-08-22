package com.druk.lmplayground.sample.chatclient.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.sample.chatclient.Availability
import com.druk.lmplayground.sample.chatclient.DemoUiState
import com.druk.lmplayground.sample.chatclient.UiMessage
import com.druk.lmplayground.sample.chatclient.UiToolCall

/**
 * A chat, built entirely on the public API.
 *
 * Nothing here imports anything from LM Playground's app module — the only
 * dependency is `:playground-api`. Everything on screen (streaming tokens,
 * collapsible reasoning, tool-call chips, capability negotiation) goes through
 * the same IPC surface any third-party app would use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: DemoUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onToggleTools: (Boolean) -> Unit,
    onToggleVision: (Boolean) -> Unit,
    onPinModel: (String?) -> Unit,
    onRefreshModels: () -> Unit,
    onOpenPlayStore: () -> Unit,
    onOpenLmPlayground: () -> Unit,
    onRetryConnect: () -> Unit,
    onRetryAuto: () -> Unit,
    onDismissError: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LMP API Demo") },
                actions = {
                    IconButton(onClick = {
                        onRefreshModels()
                        showModels = true
                    }) {
                        Icon(Icons.Outlined.Memory, contentDescription = "Models")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            StatusBanner(
                availability = state.availability,
                error = state.error,
                onOpenPlayStore = onOpenPlayStore,
                onOpenLmPlayground = onOpenLmPlayground,
                onRetryConnect = onRetryConnect,
                onRetryAuto = onRetryAuto,
                onDismissError = onDismissError,
            )

            CapabilityRow(
                state = state,
                onToggleTools = onToggleTools,
                onToggleVision = onToggleVision,
            )

            AnimatedVisibility(state.loadingModel) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(
                        "Loading a model… first token may take a few seconds.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }

            if (state.messages.isEmpty()) {
                EmptyState(state, Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages, key = { it.id }) { MessageBubble(it) }
                }
            }

            InputBar(
                value = input,
                onValueChange = { input = it },
                enabled = state.canSend,
                isGenerating = state.isGenerating,
                onSend = {
                    onSend(input)
                    input = ""
                },
                onStop = onStop,
            )
        }
    }

    if (showModels) {
        ModelSheet(
            models = state.models,
            loadedModel = state.loadedModel,
            pinnedModel = state.pinnedModel,
            onPin = onPinModel,
            onDismiss = { showModels = false },
        )
    }
}

@Composable
private fun CapabilityRow(
    state: DemoUiState,
    onToggleTools: (Boolean) -> Unit,
    onToggleVision: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // These map straight onto `lmp.require` in the request: declaring a
        // requirement is how a client asks LM Playground to refuse rather than
        // silently answer with a model that can't do the job.
        FilterChip(
            selected = state.toolsEnabled,
            onClick = { onToggleTools(!state.toolsEnabled) },
            label = { Text("Tools") },
            leadingIcon = {
                Icon(Icons.Outlined.Build, null, Modifier.size(FilterChipDefaults.IconSize))
            },
        )
        FilterChip(
            selected = state.requireVision,
            onClick = { onToggleVision(!state.requireVision) },
            label = { Text("Require vision") },
        )
    }
}

@Composable
private fun EmptyState(state: DemoUiState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                "Chat, running on-device",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                when (val availability = state.availability) {
                    is Availability.Ready ->
                        "Connected to LM Playground ${availability.appVersion}. " +
                            (state.loadedModel?.let { "Using the model it has loaded." }
                                ?: "Nothing is loaded — the first message will load one.")
                    Availability.Checking -> "Connecting…"
                    else -> "Not connected."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Turn on Tools and ask “what time is it?” — this app runs the tool itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun MessageBubble(message: UiMessage) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            if (message.reasoning.isNotBlank()) {
                ReasoningBlock(message.reasoning)
            }
            message.toolCalls.forEach { ToolCallChip(it) }

            if (message.content.isNotBlank() || message.streaming) {
                Surface(
                    color = if (message.isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(
                        16.dp, 16.dp,
                        if (message.isUser) 4.dp else 16.dp,
                        if (message.isUser) 16.dp else 4.dp,
                    ),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (message.content.isBlank() && message.streaming) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            SelectionContainer {
                                Text(
                                    message.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (message.isUser) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reasoning arrives on its own channel (`delta.reasoning_content`), so it can
 * be collapsed without the client having to string-match `<think>` tags.
 */
@Composable
private fun ReasoningBlock(reasoning: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(bottom = 4.dp).clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                if (expanded) "Reasoning ▾" else "Reasoning ▸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCallChip(call: UiToolCall) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(bottom = 4.dp).clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Build, null, Modifier.size(14.dp))
                Text(
                    "  ran ${call.name} locally",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (expanded) {
                Text(
                    "args: ${call.arguments}\nresult: ${call.result}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                enabled = enabled || isGenerating,
                maxLines = 5,
            )
            IconButton(
                onClick = if (isGenerating) onStop else onSend,
                enabled = isGenerating || (enabled && value.isNotBlank()),
                modifier = Modifier
                    .padding(start = 6.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = if (isGenerating || value.isNotBlank()) 1f else 0.3f
                        ),
                        RoundedCornerShape(24.dp),
                    ),
            ) {
                Icon(
                    if (isGenerating) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isGenerating) "Stop" else "Send",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
