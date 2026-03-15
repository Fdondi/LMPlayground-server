package com.druk.lmplayground.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

class StoragePreferences(context: Context) {

    private val prefs = context.getSharedPreferences("storage_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_URI = "model_storage_uri"
    }

    var modelStorageUri: Uri?
        get() = prefs.getString(KEY_URI, null)?.toUri()
        set(value) = prefs.edit { putString(KEY_URI, value?.toString()) }

    fun getCustomModelMetadata(filename: String): Pair<String, Boolean>? {
        val value = prefs.getString("custom_model_$filename", null) ?: return null
        val parts = value.split("|", limit = 2)
        if (parts.size != 2) return null
        return Pair(parts[0], parts[1].toBoolean())
    }

    fun setCustomModelMetadata(filename: String, name: String, hasChatTemplate: Boolean) {
        prefs.edit { putString("custom_model_$filename", "$name|$hasChatTemplate") }
    }

    fun removeCustomModelMetadata(filename: String) {
        prefs.edit { remove("custom_model_$filename") }
    }

    fun clear() {
        prefs.edit { clear() }
    }
}
