package com.druk.lmplayground.models

import android.net.Uri
import androidx.annotation.DrawableRes
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Static model definition - does not contain download status
 */
data class ModelInfo(
    val name: String,
    val filename: String,
    val remoteUri: Uri,
    val releaseDate: LocalDate,
    val inputPrefix: String = "",
    val inputSuffix: String = "",
    val antiPrompt: Array<String> = emptyArray(),
    val description: String,
    @param:DrawableRes val logoRes: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ModelInfo

        if (name != other.name) return false
        if (filename != other.filename) return false
        if (remoteUri != other.remoteUri) return false
        if (releaseDate != other.releaseDate) return false
        if (inputPrefix != other.inputPrefix) return false
        if (inputSuffix != other.inputSuffix) return false
        if (!antiPrompt.contentEquals(other.antiPrompt)) return false
        if (description != other.description) return false
        if (logoRes != other.logoRes) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + remoteUri.hashCode()
        result = 31 * result + releaseDate.hashCode()
        result = 31 * result + inputPrefix.hashCode()
        result = 31 * result + inputSuffix.hashCode()
        result = 31 * result + antiPrompt.contentHashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + logoRes
        return result
    }
}

/**
 * Model with its download status
 */
data class ModelWithStatus(
    val model: ModelInfo,
    val isDownloaded: Boolean
)

private val RELEASE_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

fun ModelInfo.releaseDateLabel(): String = releaseDate.format(RELEASE_DATE_FORMATTER)
