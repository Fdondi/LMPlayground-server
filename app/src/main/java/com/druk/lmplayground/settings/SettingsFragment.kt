package com.druk.lmplayground.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.BuildConfig
import com.druk.lmplayground.R
import com.druk.lmplayground.theme.PlaygroundTheme

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
                    appVersion = BuildConfig.VERSION_NAME
                )
            }
        }
    }
}
