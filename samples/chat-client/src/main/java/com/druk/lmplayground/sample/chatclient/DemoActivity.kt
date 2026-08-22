package com.druk.lmplayground.sample.chatclient

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.druk.lmplayground.api.LmPlaygroundApi
import com.druk.lmplayground.sample.chatclient.ui.ChatScreen
import com.druk.lmplayground.sample.chatclient.ui.DemoTheme

class DemoActivity : ComponentActivity() {

    private val viewModel: DemoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DemoTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ChatScreen(
                    state = state,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    onToggleTools = viewModel::setToolsEnabled,
                    onToggleVision = viewModel::setRequireVision,
                    onPinModel = viewModel::pinModel,
                    onRefreshModels = viewModel::refreshModels,
                    onOpenPlayStore = ::openPlayStore,
                    onOpenLmPlayground = ::openLmPlayground,
                    onRetryConnect = viewModel::connect,
                    onRetryAuto = {
                        // The most common recovery for model_mismatch: drop the
                        // pin and use whatever the user has loaded.
                        viewModel.pinModel(null)
                        viewModel.dismissError()
                    },
                    onDismissError = viewModel::dismissError,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have loaded or unloaded a model in LM Playground while
        // we were backgrounded, which changes what we can ask for.
        viewModel.refreshModels()
    }

    private fun openPlayStore() {
        val marketUri = "market://details?id=${LmPlaygroundApi.PLAY_STORE_PACKAGE}"
        val webUri =
            "https://play.google.com/store/apps/details?id=${LmPlaygroundApi.PLAY_STORE_PACKAGE}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(marketUri)))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri)))
        }
    }

    /**
     * Launch whichever LM Playground build is actually installed.
     *
     * Resolved through the API action rather than a hardcoded package, for the
     * same reason discovery is: the debug build's applicationId carries a
     * ".debug" suffix.
     */
    private fun openLmPlayground() {
        val target = packageManager
            .queryIntentServices(Intent(LmPlaygroundApi.ACTION_BIND), 0)
            .firstOrNull()
            ?.serviceInfo
            ?.packageName
        val launch = target?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) {
            startActivity(launch)
        } else {
            Toast.makeText(this, "LM Playground is not installed.", Toast.LENGTH_SHORT).show()
        }
    }
}
