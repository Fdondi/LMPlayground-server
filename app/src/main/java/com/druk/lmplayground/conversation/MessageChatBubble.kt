package com.druk.lmplayground.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

@Composable
fun ChatItemBubble(
    message: Message,
    showActions: Boolean = true,
    onTokenCountClicked: (() -> Unit)? = null
) {
    var showRatingSheet by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    val isWaitingForResponse = !showActions && message.content.isEmpty()

    Column {
        if (isWaitingForResponse) {
            ThinkingIndicator()
        } else {
            val split = remember(message.content) { splitThinking(message.content) }
            val hasThinking = split.thinkingContent.isNotEmpty()
            val isGenerating = !showActions

            if (hasThinking) {
                var expanded by remember { mutableStateOf(false) }
                val thinkingDuration = formatDuration(message.thinkingDurationSeconds)

                Row(
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse thinking" else "Expand thinking",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    val thinkingLabel = buildString {
                        append("Thinking \u00B7 $thinkingDuration")
                        if (message.thinkingTokens > 0) {
                            append(" \u00B7 ${message.thinkingTokens} tokens")
                        }
                    }
                    Text(
                        text = thinkingLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Surface {
                        SelectionContainer {
                            Text(
                                text = split.thinkingContent,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            )
                        }
                    }
                }
                if (split.responseContent.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(18.dp))
                }
            }

            if (split.responseContent.isNotEmpty()) {
                val styledMessage = messageFormatter(
                    text = split.responseContent,
                    primary = false
                )
                SelectionContainer {
                    Text(
                        text = styledMessage,
                        style = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current)
                    )
                }
            }

            if (isGenerating && message.responseTokens > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatResponseStats(message),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        message.image?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Image(
                painter = painterResource(it),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(160.dp),
                contentDescription = stringResource(id = R.string.attached_image)
            )
        }

        if (showActions) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(stripThinkTags(message.content)))
                }) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(id = R.string.copy),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = { /* upvote - no-op */ }) {
                    Icon(
                        imageVector = Icons.Outlined.ThumbUp,
                        contentDescription = stringResource(id = R.string.upvote),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = { showRatingSheet = true }) {
                    Icon(
                        imageVector = Icons.Outlined.ThumbDown,
                        contentDescription = stringResource(id = R.string.downvote),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                if (message.responseTokens > 0) {
                    Text(
                        text = formatResponseStats(message),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .then(
                                if (onTokenCountClicked != null) {
                                    Modifier.clickable(onClick = onTokenCountClicked)
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            }
        }
    }

    if (showRatingSheet) {
        RatingBottomSheet(onDismiss = { showRatingSheet = false })
    }
}

private fun formatResponseStats(message: Message): String {
    return buildString {
        append("${message.responseTokens} tokens")
        val duration = message.responseDurationSeconds
        if (duration > 0f) {
            append(" \u00B7 ${formatDuration(duration.toInt())}")
            val speed = message.responseTokens / duration
            append(" \u00B7 ${"%.1f".format(speed)} tok/s")
        }
    }
}

private fun formatDuration(seconds: Int): String {
    return if (seconds < 60) {
        "${seconds}s"
    } else {
        val m = seconds / 60
        val s = seconds % 60
        "${m}m ${s}s"
    }
}

@Composable
private fun ThinkingIndicator() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val dotCount by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dots"
    )
    val dots = ".".repeat(dotCount.toInt().coerceIn(0, 3))
    Text(
        text = "Thinking$dots",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        fontStyle = FontStyle.Italic
    )
}
