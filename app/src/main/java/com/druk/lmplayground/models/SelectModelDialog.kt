package com.druk.lmplayground.models

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.druk.lmplayground.R

@Composable
fun SelectModelDialog(
    models: List<ModelWithStatus>,
    onLoadModel: (ModelInfo) -> Unit,
    onBrowseModels: () -> Unit,
    onDismissRequest: () -> Unit
) {
    // Only show downloaded models
    val downloadedModels = models.filter { it.isDownloaded }
    
    Dialog(onDismissRequest = { onDismissRequest() }) {
        Card(
            modifier = Modifier
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
                    for (index in downloadedModels.indices) {
                        val modelWithStatus = downloadedModels[index]
                        item {
                            Model(model = modelWithStatus.model) {
                                onDismissRequest()
                                onLoadModel(modelWithStatus.model)
                            }
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
                    Spacer(modifier = Modifier.height(8.dp))
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
