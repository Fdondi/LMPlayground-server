package com.druk.lmplayground.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.druk.lmplayground.tools.Tool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationParamsSheet(
    params: GenerationParams,
    maxContextSize: Int,
    supportsThinking: Boolean = false,
    supportsToolCalling: Boolean = false,
    tools: List<Tool> = emptyList(),
    toolEnabledStates: Map<String, Boolean> = emptyMap(),
    onToolEnabledChanged: (String, Boolean) -> Unit = { _, _ -> },
    onParamsChanged: (GenerationParams) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editedParams by remember(params) { mutableStateOf(params) }
    var showAdvanced by remember { mutableStateOf(false) }

    val contextMin = 512
    val contextMax = maxContextSize.coerceAtLeast(512)
    val contextStep = 512

    ModalBottomSheet(
        onDismissRequest = {
            if (editedParams != params) {
                onParamsChanged(editedParams)
            }
            onDismiss()
        },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Generation Parameters",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = {
                    val defaults = GenerationParams()
                    editedParams = defaults
                }) {
                    Text("Reset")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Context Size
            val contextWarning = editedParams.contextSize != params.contextSize
            ParamSlider(
                label = "Context Size",
                value = editedParams.contextSize.toFloat(),
                valueRange = contextMin.toFloat()..contextMax.toFloat(),
                steps = (((contextMax - contextMin) / contextStep) - 1).coerceAtLeast(0),
                valueDisplay = "${editedParams.contextSize}",
                warning = if (contextWarning) "Will reset conversation" else null,
                onValueChange = {
                    val snapped = (it / contextStep).roundToInt() * contextStep
                    val newContextSize = snapped.coerceIn(contextMin, contextMax)
                    val oldContextSize = editedParams.contextSize
                    // Auto-scale thinking budget proportionally when context size changes
                    val newBudget = if (oldContextSize > 0) {
                        (editedParams.thinkingBudget.toLong() * newContextSize / oldContextSize).toInt()
                            .coerceIn(64, newContextSize)
                    } else {
                        newContextSize / 4
                    }
                    editedParams = editedParams.copy(contextSize = newContextSize, thinkingBudget = newBudget)
                }
            )

            // Thinking Budget (only for thinking-capable models)
            if (supportsThinking) {
                val budgetMin = 64
                val budgetMax = editedParams.contextSize
                ParamSlider(
                    label = "Thinking Budget",
                    value = editedParams.thinkingBudget.toFloat(),
                    valueRange = budgetMin.toFloat()..budgetMax.toFloat(),
                    steps = 0,
                    valueDisplay = "${editedParams.thinkingBudget} tokens",
                    onValueChange = {
                        val snapped = (it / 64).roundToInt() * 64
                        editedParams = editedParams.copy(
                            thinkingBudget = snapped.coerceIn(budgetMin, budgetMax)
                        )
                    }
                )
            }

            // Tools section (only for models that support tool calling)
            if (supportsToolCalling && tools.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tools",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Model can call these during generation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                for (tool in tools) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = tool.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Switch(
                            checked = toolEnabledStates[tool.name] ?: true,
                            onCheckedChange = { onToolEnabledChanged(tool.name, it) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Temperature
            ParamSlider(
                label = "Temperature",
                value = editedParams.temperature,
                valueRange = 0f..2f,
                steps = 0,
                valueDisplay = "%.2f".format(editedParams.temperature),
                onValueChange = {
                    editedParams = editedParams.copy(temperature = (it * 100).roundToInt() / 100f)
                }
            )

            // Top-P
            ParamSlider(
                label = "Top-P",
                value = editedParams.topP,
                valueRange = 0f..1f,
                steps = 0,
                valueDisplay = "%.2f".format(editedParams.topP),
                subtitle = if (editedParams.topP >= 1f) "disabled" else null,
                onValueChange = {
                    editedParams = editedParams.copy(topP = (it * 100).roundToInt() / 100f)
                }
            )

            // Repetition Penalty
            ParamSlider(
                label = "Repetition Penalty",
                value = editedParams.repetitionPenalty,
                valueRange = 1f..2f,
                steps = 0,
                valueDisplay = "%.2f".format(editedParams.repetitionPenalty),
                subtitle = if (editedParams.repetitionPenalty <= 1f) "disabled" else null,
                onValueChange = {
                    editedParams = editedParams.copy(repetitionPenalty = (it * 100).roundToInt() / 100f)
                }
            )

            // Advanced section
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvanced = !showAdvanced }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Advanced",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (showAdvanced) "Collapse" else "Expand"
                )
            }

            AnimatedVisibility(visible = showAdvanced) {
                Column {
                    // Top-K
                    ParamSlider(
                        label = "Top-K",
                        value = editedParams.topK.toFloat(),
                        valueRange = 0f..200f,
                        steps = 0,
                        valueDisplay = "${editedParams.topK}",
                        subtitle = if (editedParams.topK == 0) "disabled" else null,
                        onValueChange = {
                            editedParams = editedParams.copy(topK = it.roundToInt())
                        }
                    )

                    // Min-P
                    ParamSlider(
                        label = "Min-P",
                        value = editedParams.minP,
                        valueRange = 0f..0.5f,
                        steps = 0,
                        valueDisplay = "%.3f".format(editedParams.minP),
                        subtitle = if (editedParams.minP <= 0f) "disabled" else null,
                        onValueChange = {
                            editedParams = editedParams.copy(minP = (it * 1000).roundToInt() / 1000f)
                        }
                    )

                    // Seed
                    var seedText by remember(editedParams.seed) {
                        mutableStateOf(if (editedParams.seed < 0) "" else editedParams.seed.toString())
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Seed",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = seedText,
                            onValueChange = { text ->
                                seedText = text
                                val value = text.toIntOrNull() ?: -1
                                editedParams = editedParams.copy(seed = value)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp),
                            placeholder = { Text("Random") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueDisplay: String,
    subtitle: String? = null,
    warning: String? = null,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                if (subtitle != null) {
                    Text(
                        text = " ($subtitle)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
        if (warning != null) {
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
