@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.conversation

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.AppBar
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.theme.PlaygroundTheme
import java.time.LocalDate

const val ConversationBarTestTag = "ConversationBarTestTag"

@Composable
fun ConversationBar(
    modelInfo: ModelInfo?,
    modelStatus: String? = null,
    modifier: Modifier = Modifier,
    colors: TopAppBarColors? = null,
    onModelNamePressed: () -> Unit = { },
    onNavIconPressed: () -> Unit = { },
    onNewSessionPressed: () -> Unit = { }
) {
    AppBar(
        modifier = modifier.testTag(ConversationBarTestTag),
        colors = colors,
        onNavIconPressed = onNavIconPressed,
        title = {
            if (modelInfo != null) {
                Surface(
                    onClick = onModelNamePressed,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0f),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = modelInfo.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                            Icon(
                                imageVector = Icons.Outlined.ArrowDropDown,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                contentDescription = null
                            )
                        }
                        Text(
                            text = modelStatus ?: modelInfo.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Surface(
                    onClick = onModelNamePressed,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.select_model),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Icon(
                            imageVector = Icons.Outlined.ArrowDropDown,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            contentDescription = null
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onNewSessionPressed) {
                Icon(
                    imageVector = Icons.Outlined.EditNote,
                    contentDescription = stringResource(R.string.new_conversation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Preview
@Composable
fun ChannelBarPreview() {
    PlaygroundTheme {
        ConversationBar(modelInfo = null)
    }
}

@Preview("Bar with model info")
@Composable
fun ChannelBarWithModelInfoPreview() {
    PlaygroundTheme {
        ConversationBar(
            modelInfo = ModelInfo(
                name = "Model Name Model Name Model Name Model Name Model Name",
                filename = "model.gguf",
                remoteUri = Uri.parse("https://example.com/model.gguf"),
                releaseDate = LocalDate.parse("2025-01-01"),
                description = "Model Description"
            ),
            modelStatus = "Loading 50%"
        )
    }
}
