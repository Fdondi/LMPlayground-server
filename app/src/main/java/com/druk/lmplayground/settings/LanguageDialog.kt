package com.druk.lmplayground.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.druk.lmplayground.R
import java.util.Locale

/**
 * One entry in the language picker. `tag` is null for "System default".
 */
data class LanguageOption(
    val tag: String?,
    val displayName: String
)

/**
 * BCP-47 language tags supported by the app. Must stay in sync with
 * `app/src/main/res/xml/locales_config.xml`.
 */
private val SUPPORTED_LANGUAGE_TAGS = listOf(
    "en", "ar", "bn", "cs", "da", "de", "es", "fi", "fil", "fr",
    "he", "hi", "id", "it", "ja", "ko", "ms", "nb", "nl", "pl",
    "pt-BR", "ro", "sv", "th", "tr", "uk", "vi", "zh-CN"
)

/**
 * Build the list of choices shown in the picker. Each language is labeled in its own
 * locale (e.g. "Deutsch", "日本語") so users can find their language without reading
 * the app's current UI language. The list is sorted alphabetically by display name.
 */
fun buildLanguageOptions(): List<LanguageOption> {
    val systemDefault = LanguageOption(tag = null, displayName = "")
    val languages = SUPPORTED_LANGUAGE_TAGS
        .map { tag ->
            val locale = Locale.forLanguageTag(tag)
            val name = locale.getDisplayName(locale)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            LanguageOption(tag = tag, displayName = name)
        }
        .sortedBy { it.displayName.lowercase(Locale.getDefault()) }
    return listOf(systemDefault) + languages
}

/**
 * Return the BCP-47 tag currently applied by AppCompat, or null if the app follows the
 * system default.
 */
fun currentLanguageTag(): String? {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return null
    return locales.toLanguageTags().split(",").firstOrNull()?.takeIf { it.isNotBlank() }
}

@Composable
fun LanguageDialog(
    onDismiss: () -> Unit
) {
    val options = remember { buildLanguageOptions() }
    val currentTag = currentLanguageTag()
    val listState = rememberLazyListState()

    // Scroll the current selection into view when the dialog opens.
    LaunchedEffect(currentTag) {
        val idx = options.indexOfFirst { it.tag == currentTag }.coerceAtLeast(0)
        listState.scrollToItem(idx)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                items(options, key = { it.tag ?: "__system__" }) { option ->
                    val label = option.displayName.ifEmpty {
                        stringResource(R.string.language_system_default)
                    }
                    LanguageRow(
                        label = label,
                        selected = option.tag == currentTag,
                        onClick = {
                            applyLanguage(option.tag)
                            onDismiss()
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun LanguageRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

private fun applyLanguage(tag: String?) {
    val locales = if (tag.isNullOrEmpty()) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(tag)
    }
    AppCompatDelegate.setApplicationLocales(locales)
}
