package com.druk.lmplayground.sample.chatclient.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.api.model.ModelEntry

/**
 * The models list, straight from `listModels()`.
 *
 * The point of showing `verified` here is honesty about a real subtlety of the
 * protocol: until a model has been loaded once, its advertised capabilities are
 * catalog hints rather than facts read from the GGUF's chat template. A client
 * that matches requirements against unverified flags can still be surprised
 * after the load — which is why LM Playground re-checks and returns
 * `capability_unavailable` rather than answering with a model that can't do the
 * job.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    models: List<ModelEntry>,
    loadedModel: String?,
    pinnedModel: String?,
    onPin: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Model", style = MaterialTheme.typography.titleMedium)
            Text(
                "LM Playground will not unload the model its user has open. Pinning a " +
                    "different one fails with model_mismatch rather than evicting theirs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            AutoRow(pinnedModel == null) { onPin(null); onDismiss() }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            if (models.isEmpty()) {
                Text(
                    "No models downloaded. Open LM Playground to get one.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn {
                    items(models, key = { it.id }) { model ->
                        ModelRow(
                            model = model,
                            selected = pinnedModel == model.id,
                            isLoaded = model.id == loadedModel,
                            onClick = { onPin(model.id); onDismiss() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoRow(selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text("Auto", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Use whatever is loaded, or load the smallest model that meets the " +
                    "requirements.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelEntry,
    selected: Boolean,
    isLoaded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.displayName.ifBlank { model.id },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (isLoaded) {
                    Text(
                        "  • loaded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (model.capabilities.vision) CapabilityChip("vision")
                if (model.capabilities.tools) CapabilityChip("tools")
                if (model.capabilities.thinking) CapabilityChip("thinking")
                if (!model.capabilities.verified) CapabilityChip("unverified")
            }
            model.capabilities.maxContext?.let {
                Text(
                    "context $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CapabilityChip(label: String) {
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(),
        enabled = false,
    )
}
