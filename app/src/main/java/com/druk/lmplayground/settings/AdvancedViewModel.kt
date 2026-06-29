package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.druk.lmplayground.storage.StoragePreferences

/**
 * Backs Settings → Advanced. Currently a single toggle: "Disable repack",
 * OFF by default. The value is read by
 * [com.druk.lmplayground.conversation.ConversationViewModel] at model-load
 * time straight from [StoragePreferences], so a change takes effect on the
 * next load.
 */
class AdvancedViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)

    private val _disableRepack = MutableLiveData(prefs.disableRepack)
    val disableRepack: LiveData<Boolean> = _disableRepack

    fun setDisableRepack(value: Boolean) {
        prefs.disableRepack = value
        _disableRepack.value = value
    }
}
