package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.druk.lmplayground.tools.Tool
import com.druk.lmplayground.tools.ToolRegistry

/**
 * Backs the Settings → Tools screen. Reads and writes the *global default*
 * enablement for each tool (Settings → Tools). A per-model override, set from a
 * model's generation-params sheet, takes precedence at load time — see
 * [StoragePreferences.effectiveToolEnabled].
 */
class ToolsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)

    init {
        // Opening the Tools screen counts as having "set up tools" — the
        // What's New prompt button is suppressed from now on (re-checked by
        // ConversationViewModel.refreshToolsSetupVisibility on resume).
        prefs.toolsSetupSeen = true
    }

    /** The catalog of available tools (same set the conversation engine uses). */
    val tools: List<Tool> = ToolRegistry.createDefault(app).getAllTools()

    private val _enabled = MutableStateFlow(
        tools.associate { it.name to prefs.isToolEnabledDefault(it.name) }
    )
    val enabled: StateFlow<Map<String, Boolean>> = _enabled

    fun setEnabled(toolName: String, value: Boolean) {
        prefs.setToolEnabledDefault(toolName, value)
        _enabled.value = _enabled.value.toMutableMap().apply { put(toolName, value) }
    }
}
