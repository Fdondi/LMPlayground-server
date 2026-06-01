package com.druk.lmplayground.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.druk.lmplayground.R

@Composable
fun SelectModelDialog(
    models: List<ModelWithStatus>,
    isModelLoaded: Boolean = false,
    /**
     * When > 0, the dialog Card is centered inside the chat pane rather than
     * the full window — used on tablet so the picker doesn't appear to cover
     * the sessions sidebar on its way to the chat area. Pass the sidebar's
     * effective width here (e.g. `320.dp`).
     */
    chatPaneStartOffset: Dp = 0.dp,
    onLoadModel: (ModelInfo) -> Unit,
    onUnloadModel: () -> Unit = {},
    onGenerationParams: () -> Unit = {},
    onBrowseModels: () -> Unit,
    onDismissRequest: () -> Unit
) {
    // Only show downloaded models
    val downloadedModels = models.filter { it.isDownloaded }

    // usePlatformDefaultWidth=false lets us cap the dialog at a comfortable
    // 560dp on freeform Chromebook windows / wide tablets while still letting
    // it expand to fill narrow phones via fillMaxWidth.
    Dialog(
        onDismissRequest = { onDismissRequest() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            // Full-screen Box gives us explicit alignment control. Adding a
            // start padding equal to the sidebar width means
            // contentAlignment = Center centers within (sidebar..rightEdge)
            // rather than the full window.
            modifier = Modifier
                .fillMaxSize()
                .padding(start = chatPaneStartOffset),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
            LazyColumn {
                if (downloadedModels.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.no_downloaded_models),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    items(
                        items = downloadedModels,
                        key = { it.model.filename }
                    ) { modelWithStatus ->
                        Model(model = modelWithStatus.model) {
                            onDismissRequest()
                            onLoadModel(modelWithStatus.model)
                        }
                    }
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            onDismissRequest()
                            onBrowseModels()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(stringResource(R.string.browse_more_models))
                    }
                    if (isModelLoaded) {
                        TextButton(
                            onClick = {
                                onDismissRequest()
                                onGenerationParams()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(stringResource(R.string.generation_parameters))
                        }
                        TextButton(
                            onClick = {
                                onDismissRequest()
                                onUnloadModel()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Eject,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(stringResource(R.string.unload_model))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
        }
    }
}

@Composable
fun Model(
    model: ModelInfo,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (model.logoRes != 0) {
                Image(
                    painter = painterResource(id = model.logoRes),
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                    maxLines = 1
                )
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    maxLines = 1
                )
                if (model.releaseDate != null) {
                    Text(
                        text = model.releaseDateLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        maxLines = 1
                    )
                }
            }
        }
        ModelCapabilityIcons(
            model = model,
            modifier = Modifier.padding(end = 4.dp)
        )
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            modifier = Modifier.padding(4.dp),
            tint = MaterialTheme.colorScheme.onSurface,
            contentDescription = null
        )
    }
}

@Preview
@Composable
fun SelectModelDialogPreview() {
    SelectModelDialog(
        models = ModelInfoProvider.allModels.take(3).mapIndexed { index, model ->
            ModelWithStatus(model = model, isDownloaded = index < 2)
        },
        onLoadModel = { },
        onBrowseModels = { },
        onDismissRequest = { }
    )
}
