package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Backs Settings → Advanced.
 *
 * "Enable repack" is read by
 * [com.druk.lmplayground.conversation.ConversationViewModel] at model-load time
 * straight from [StoragePreferences], so a change takes effect on the next
 * load. "Allow other apps" is checked per binder transaction by
 * [com.druk.lmplayground.api.UserToggleAccessPolicy], so it takes effect
 * immediately — including on a client that is already bound.
 */
class AdvancedViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)

    private val _repackEnabled = MutableStateFlow(prefs.repackEnabled)
    val repackEnabled: StateFlow<Boolean> = _repackEnabled

    private val _externalApiEnabled = MutableStateFlow(prefs.externalApiEnabled)
    val externalApiEnabled: StateFlow<Boolean> = _externalApiEnabled

    fun setRepackEnabled(value: Boolean) {
        prefs.repackEnabled = value
        _repackEnabled.value = value
    }

    fun setExternalApiEnabled(value: Boolean) {
        prefs.externalApiEnabled = value
        _externalApiEnabled.value = value
    }
}
