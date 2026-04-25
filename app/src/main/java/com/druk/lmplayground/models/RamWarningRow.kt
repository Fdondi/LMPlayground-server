package com.druk.lmplayground.models

import android.text.format.Formatter
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

/**
 * Inline RAM-fit warning shared by the model picker
 * ([com.druk.lmplayground.models.SelectModelDialog]) and the download
 * list ([com.druk.lmplayground.storage.ModelsScreen]). Renders nothing
 * for [DeviceCapability.FitVerdict.Fit].
 *
 * @param startPadding leading inset so the warning aligns under the
 *   text column of the parent row, regardless of whether the parent
 *   uses a logo / icon spacer of different width.
 */
@Composable
fun RamWarningRow(
    verdict: DeviceCapability.FitVerdict,
    modelSizeBytes: Long,
    totalRamBytes: Long,
    availRamBytes: Long,
    startPadding: androidx.compose.ui.unit.Dp = 24.dp,
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
            .padding(start = startPadding, end = 16.dp, bottom = 8.dp)
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
