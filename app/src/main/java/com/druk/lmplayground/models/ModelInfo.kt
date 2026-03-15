package com.druk.lmplayground.models

import android.net.Uri
import androidx.annotation.DrawableRes
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Static model definition - does not contain download status.
 * Chat template parameters (prefix, suffix, stop sequences) are read
 * from the GGUF file's embedded Jinja template at load time.
 */
data class ModelInfo(
    val name: String,
    val filename: String,
    val remoteUri: Uri? = null,
    val releaseDate: LocalDate? = null,
    val description: String,
    @param:DrawableRes val logoRes: Int = 0
) {
    val isCustom: Boolean get() = remoteUri == null
}

/**
 * Model with its download status
 */
data class ModelWithStatus(
    val model: ModelInfo,
    val isDownloaded: Boolean
)

private val RELEASE_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

fun ModelInfo.releaseDateLabel(): String = releaseDate?.format(RELEASE_DATE_FORMATTER) ?: ""
