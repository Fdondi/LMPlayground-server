package com.druk.lmplayground.settings

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import com.druk.llamacpp.InferenceState
import com.druk.lmplayground.App
import com.druk.lmplayground.BuildConfig
import com.druk.lmplayground.R
import com.druk.lmplayground.theme.PlaygroundTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    /**
     * Rapid double-taps on a settings row can fire the second click after the
     * first has already navigated away from nav_settings, at which point the
     * action ID is no longer known to the NavController and navigate() throws
     * IllegalArgumentException. Guard every navigation by verifying the
     * NavController is still at nav_settings before firing the action.
     */
    private fun NavController.navigateFromSettings(actionId: Int) {
        if (currentDestination?.id == R.id.nav_settings) {
            navigate(actionId)
        }
    }

    /**
     * Debug-only: trigger a Process.killProcess(myPid) inside `:llama` so we
     * can verify that crashes don't take the UI down and that the
     * acknowledge → reload recovery path works.
     */
    private fun crashInferenceEngine() {
        val app = activity?.application as? App ?: return
        val client = app.inferenceClient
        val service = (client.state.value as? InferenceState.Connected)?.service
        if (service == null) {
            Toast.makeText(
                requireContext(),
                R.string.debug_crash_engine_not_connected,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        // Run off the main thread — the binder call will throw DeadObject
        // because the process dies mid-transaction; that's expected.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    service.crashForTest()
                } catch (t: Throwable) {
                    Log.d(TAG, "crashForTest returned with expected error: ${t.javaClass.simpleName}")
                }
            }
            Toast.makeText(
                requireContext(),
                R.string.debug_crash_engine_done,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    companion object {
        private const val TAG = "SettingsFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                SettingsScreen(
                    onBackClick = { findNavController().popBackStack() },
                    onModelsClick = {
                        findNavController().navigateFromSettings(R.id.action_settings_to_models)
                    },
                    onSystemPromptsClick = {
                        findNavController().navigateFromSettings(R.id.action_settings_to_system_prompts)
                    },
                    onPrivacyPolicyClick = {
                        findNavController().navigateFromSettings(R.id.action_settings_to_privacy_policy)
                    },
                    onFaqClick = {
                        findNavController().navigateFromSettings(R.id.action_settings_to_faq)
                    },
                    appVersion = BuildConfig.VERSION_NAME,
                    // Debug builds expose a button to crash the inference
                    // process so we can verify the recovery flow without
                    // waiting for an organic SIGSEGV.
                    onCrashEngineClick = if (BuildConfig.DEBUG) {
                        { crashInferenceEngine() }
                    } else null,
                )
            }
        }
    }
}
