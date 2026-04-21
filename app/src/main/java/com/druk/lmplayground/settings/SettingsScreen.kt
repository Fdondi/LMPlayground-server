@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.theme.PlaygroundTheme
import java.util.Locale

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onModelsClick: () -> Unit,
    onSystemPromptsClick: () -> Unit,
    onPrivacyPolicyClick: () -> Unit,
    onFaqClick: () -> Unit,
    appVersion: String
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        var showLanguageDialog by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Models row
            SettingsRow(
                icon = Icons.Outlined.Storage,
                title = stringResource(R.string.models),
                subtitle = stringResource(R.string.models_subtitle),
                onClick = onModelsClick
            )

            // Language row
            val currentTag = currentLanguageTag()
            val languageSubtitle = if (currentTag == null) {
                stringResource(R.string.language_system_default)
            } else {
                Locale.forLanguageTag(currentTag).let { locale ->
                    locale.getDisplayName(locale)
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
                }
            }
            SettingsRow(
                icon = Icons.Outlined.Language,
                title = stringResource(R.string.language),
                subtitle = languageSubtitle,
                onClick = { showLanguageDialog = true }
            )

            if (showLanguageDialog) {
                LanguageDialog(onDismiss = { showLanguageDialog = false })
            }

            // System Prompts row
            SettingsRow(
                icon = Icons.AutoMirrored.Outlined.Article,
                title = stringResource(R.string.system_prompts),
                subtitle = stringResource(R.string.system_prompts_subtitle),
                onClick = onSystemPromptsClick
            )

            // Privacy Policy row
            SettingsRow(
                icon = Icons.Outlined.Policy,
                title = stringResource(R.string.privacy_policy),
                onClick = onPrivacyPolicyClick
            )

            // FAQ row
            SettingsRow(
                icon = Icons.Outlined.HelpOutline,
                title = stringResource(R.string.faq),
                subtitle = stringResource(R.string.faq_subtitle),
                onClick = onFaqClick
            )

            // Version row (static, not clickable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.version, appVersion),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    PlaygroundTheme {
        SettingsScreen(
            onBackClick = {},
            onModelsClick = {},
            onSystemPromptsClick = {},
            onPrivacyPolicyClick = {},
            onFaqClick = {},
            appVersion = "1.0.0"
        )
    }
}
