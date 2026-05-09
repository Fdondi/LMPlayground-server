@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.data.SystemPromptEntity

@Composable
fun SystemPromptsScreen(
    prompts: List<SystemPromptEntity>,
    onBackClick: () -> Unit,
    onAdd: (String) -> Unit,
    onUpdate: (id: String, text: String) -> Unit,
    onDelete: (id: String) -> Unit
) {
    var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_prompts)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editorTarget = EditorTarget.New }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add))
            }
        }
    ) { padding ->
        if (prompts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.system_prompts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                items(prompts, key = { it.id }) { prompt ->
                    PromptCard(
                        prompt = prompt,
                        onClick = { editorTarget = EditorTarget.Edit(prompt) }
                    )
                }
            }
        }
    }

    when (val target = editorTarget) {
        EditorTarget.New -> SystemPromptEditorSheet(
            initialText = "",
            title = stringResource(R.string.system_prompt_new),
            primaryLabel = stringResource(R.string.save),
            onPrimary = { text -> onAdd(text) },
            onDismiss = { editorTarget = null }
        )
        is EditorTarget.Edit -> SystemPromptEditorSheet(
            initialText = target.prompt.text,
            title = stringResource(R.string.system_prompt_edit),
            primaryLabel = stringResource(R.string.save),
            onPrimary = { text -> onUpdate(target.prompt.id, text) },
            onDelete = { onDelete(target.prompt.id) },
            onDismiss = { editorTarget = null }
        )
        null -> Unit
    }
}

@Composable
private fun PromptCard(
    prompt: SystemPromptEntity,
    onClick: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = prompt.text,
                style = MaterialTheme.typography.bodySmall,
                minLines = 3,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private sealed class EditorTarget {
    object New : EditorTarget()
    data class Edit(val prompt: SystemPromptEntity) : EditorTarget()
}
