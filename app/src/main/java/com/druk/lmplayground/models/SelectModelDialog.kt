package com.druk.lmplayground.models

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    isModelLoaded: Boolean = false,
    onLoadModel: (ModelInfo) -> Unit,
    onUnloadModel: () -> Unit = {},
    onGenerationParams: () -> Unit = {},
    onBrowseModels: () -> Unit,
    onDismissRequest: () -> Unit
) {
    // Only show downloaded models
    val downloadedModels = models.filter { it.isDownloaded }

    val context = LocalContext.current
    // Snapshot RAM at dialog open time. Cheap to call repeatedly but
    // dialog lifetime is short so once is fine.
    val totalRamBytes = remember { DeviceCapability.totalRamBytes(context) }
    val availRamBytes = remember { DeviceCapability.availableRamBytes(context) }

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
                    items(
                        items = downloadedModels,
                        key = { it.model.filename }
                    ) { modelWithStatus ->
                        val verdict = DeviceCapability.evaluateFit(
                            modelSizeBytes = modelWithStatus.sizeBytes,
                            totalRamBytes = totalRamBytes,
                            availRamBytes = availRamBytes,
                        )
                        Column {
                            Model(model = modelWithStatus.model) {
                                onDismissRequest()
                                onLoadModel(modelWithStatus.model)
                            }
                            if (verdict != DeviceCapability.FitVerdict.Fit &&
                                modelWithStatus.sizeBytes > 0
                            ) {
                                RamWarningRow(
                                    verdict = verdict,
                                    modelSizeBytes = modelWithStatus.sizeBytes,
                                    totalRamBytes = totalRamBytes,
                                    availRamBytes = availRamBytes,
                                )
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

/**
 * Inline warning shown beneath a downloaded-model row when its RAM
 * footprint is borderline (Tight) or exceeds the device's safe envelope
 * (WontFit). The hard refusal happens later in
 * [com.druk.lmplayground.conversation.ConversationViewModel.loadModel];
 * this row is the user-visible heads-up so they can pick a smaller model
 * before tapping.
 */
@Composable
private fun RamWarningRow(
    verdict: DeviceCapability.FitVerdict,
    modelSizeBytes: Long,
    totalRamBytes: Long,
    availRamBytes: Long,
) {
    val context = LocalContext.current
    val sizeLabel = Formatter.formatFileSize(context, modelSizeBytes)
    val (text, tint) = when (verdict) {
        DeviceCapability.FitVerdict.WontFit -> stringResource(
            R.string.ram_warning_wont_fit,
            sizeLabel,
            Formatter.formatFileSize(context, totalRamBytes),
        ) to MaterialTheme.colorScheme.error
        DeviceCapability.FitVerdict.Tight -> stringResource(
            R.string.ram_warning_tight,
            sizeLabel,
            Formatter.formatFileSize(context, availRamBytes),
        ) to MaterialTheme.colorScheme.tertiary
        DeviceCapability.FitVerdict.Fit -> return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, bottom = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
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
