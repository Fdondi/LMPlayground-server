package com.druk.lmplayground.conversation

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.MainActivity
import com.druk.lmplayground.R
import com.druk.lmplayground.models.SelectModelDialog
import com.druk.lmplayground.storage.StorageViewModel
import com.druk.lmplayground.theme.PlaygroundTheme
import kotlinx.coroutines.launch

class ConversationFragment : Fragment() {

    private val viewModel: ConversationViewModel by viewModels()
    private val storageViewModel: StorageViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)

        setContent {

            val messages = viewModel.uiState.messages
            val isGenerating by viewModel.isGenerating.observeAsState()
            val progress by viewModel.modelLoadingProgress.observeAsState(0f)
            val modelInfo by viewModel.loadedModel.observeAsState()
            val modelStatus by viewModel.loadedModelStatus.observeAsState()
            val supportsThinking by viewModel.supportsThinking.observeAsState(false)
            val thinkingEnabled by viewModel.thinkingEnabled.observeAsState(false)
            val isModelReady by viewModel.isModelReady.observeAsState(false)
            val models by viewModel.models.observeAsState(emptyList())
            val sessions by viewModel.sessions.observeAsState(emptyList())
            val currentSessionId by viewModel.currentSessionId.observeAsState()
            val generationParams by viewModel.generationParams.observeAsState(GenerationParams())
            val maxContextSize by viewModel.maxContextSize.observeAsState(4096)
            val sessionModelHint by viewModel.sessionModelHint.observeAsState()
            val systemPrompt by viewModel.systemPrompt.observeAsState("")
            val systemPromptId by viewModel.systemPromptId.observeAsState()
            val recentSystemPrompts by viewModel.recentSystemPrompts.observeAsState(emptyList())
            val userError by viewModel.userError.observeAsState()
            val pendingRamWarning by viewModel.pendingRamWarning.observeAsState()
            val modelLoadError by viewModel.modelLoadError.observeAsState()
            var showParamsSheet by remember { mutableStateOf(false) }

            // Surface transient ViewModel errors (e.g. message-too-large)
            // as Toasts. The ViewModel can't show UI directly, so we
            // observe a one-shot LiveData and clear it after consumption.
            val toastContext = LocalContext.current
            LaunchedEffect(userError) {
                val msg = userError ?: return@LaunchedEffect
                Toast.makeText(toastContext, msg, Toast.LENGTH_LONG).show()
                viewModel.consumeUserError()
            }

            // Storage configuration state
            val isStorageConfigured by storageViewModel.isStorageConfigured.observeAsState(true)
            var showStorageSetupDialog by remember { mutableStateOf(false) }

            // Migration state
            val pendingMigration by storageViewModel.pendingMigration.observeAsState()
            val migrationProgress by storageViewModel.migrationProgress.observeAsState()

            PlaygroundTheme {

                val scrollState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val drawerState = rememberDrawerState(DrawerValue.Closed)

                val colorScheme = MaterialTheme.colorScheme

                // Drive toolbar container color directly from scroll position.
                // On tablet (where we draw an explicit divider below the bar)
                // the recoloring would just look like a half-screen color flash
                // restricted to the chat pane, so we freeze the bar at surface
                // there.
                // Only show the permanent sidebar when there's enough actual
                // window width to comfortably fit master (320dp) + detail.
                // At Medium (600-839dp), e.g. 7" tablet portrait or split-screen,
                // the chat pane would be squeezed to ~280dp — worse than just
                // hiding the sidebar behind a hamburger.
                val widthSize = calculateWindowSizeClass(requireActivity()).widthSizeClass
                val showPermanent = widthSize == WindowWidthSizeClass.Expanded

                // Foldable book posture: if a vertical hinge is in the window,
                // align the sidebar boundary with it so the seam between
                // master/detail lands on the crease instead of arbitrarily
                // bisecting one display. Fall back to 320dp on flat devices.
                val hingeWidth = com.druk.lmplayground.util.rememberHingeWidthDp(requireActivity())
                val sidebarWidth = if (hingeWidth != androidx.compose.ui.unit.Dp.Unspecified) {
                    // Clamp the lower bound so the master pane doesn't shrink
                    // below the comfortable 320dp the rest of the UI expects.
                    maxOf(320.dp, hingeWidth)
                } else {
                    320.dp
                }

                val isScrolled by remember {
                    derivedStateOf {
                        scrollState.firstVisibleItemIndex > 0 ||
                                scrollState.firstVisibleItemScrollOffset > 0
                    }
                }
                // True when there's content below the visible viewport — i.e.
                // the message list is hidden behind the input dock. Used to
                // toggle the chat/input divider so it appears only when there's
                // a real edge to mark.
                val canScrollDown by remember {
                    derivedStateOf { scrollState.canScrollForward }
                }
                val topBarColors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = if (!showPermanent && isScrolled)
                        colorScheme.surfaceContainer
                    else
                        colorScheme.surface
                )
                val inputFocusRequester = remember { FocusRequester() }
                var modelReport by remember { mutableStateOf<String?>(null) }

                // When model finishes loading, jump to bottom and open keyboard
                LaunchedEffect(isModelReady) {
                    if (isModelReady) {
                        val lastIndex = scrollState.layoutInfo.totalItemsCount - 1
                        if (lastIndex >= 0) {
                            scrollState.animateScrollToItem(lastIndex)
                        }
                        inputFocusRequester.requestFocus()
                    }
                }

                // Check if storage is configured on first launch
                LaunchedEffect(Unit) {
                    storageViewModel.checkStorageConfigured()
                }

                // Show setup dialog if storage not configured
                LaunchedEffect(isStorageConfigured) {
                    if (!isStorageConfigured) {
                        showStorageSetupDialog = true
                    }
                }

                // Storage Setup Dialog
                if (showStorageSetupDialog && !isStorageConfigured && pendingMigration == null) {
                    AlertDialog(
                        onDismissRequest = { /* Cannot dismiss - must choose folder */ },
                        title = { Text(stringResource(R.string.choose_storage_folder)) },
                        text = {
                            Text(stringResource(R.string.choose_storage_folder_message))
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    (activity as? MainActivity)?.launchFolderPicker { uri ->
                                        if (uri != null) {
                                            storageViewModel.requestStorageFolderChange(uri)
                                            showStorageSetupDialog = false
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.choose_folder))
                            }
                        }
                    )
                }

                // Migration confirmation dialog
                pendingMigration?.let { migration ->
                    val totalSize = migration.modelsToMigrate.sumOf { it.sizeBytes }
                    val sizeFormatted = android.text.format.Formatter.formatFileSize(context, totalSize)

                    AlertDialog(
                        onDismissRequest = { storageViewModel.cancelMigration() },
                        title = { Text(stringResource(R.string.migrate_models_title)) },
                        text = {
                            Column {
                                Text(
                                    if (migration.isFromDownloads) {
                                        stringResource(R.string.migrate_models_from_downloads, migration.modelsToMigrate.size, sizeFormatted)
                                    } else {
                                        stringResource(R.string.migrate_models_message, migration.modelsToMigrate.size, sizeFormatted)
                                    }
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { storageViewModel.confirmMigration() }) {
                                Text(stringResource(R.string.migrate))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { storageViewModel.skipMigration() }) {
                                Text(stringResource(R.string.skip))
                            }
                        }
                    )
                }

                // Migration progress dialog
                migrationProgress?.let { progress ->
                    AlertDialog(
                        onDismissRequest = { /* Cannot dismiss while migrating */ },
                        title = { Text(stringResource(R.string.migrating_models)) },
                        text = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(stringResource(R.string.migration_progress, progress.currentIndex, progress.totalCount))
                                Text(
                                    text = progress.currentModel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = { }
                    )
                }

                pendingRamWarning?.let { warning ->
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissRamWarning() },
                        title = { Text(stringResource(R.string.low_ram_warning_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.low_ram_warning_message,
                                    warning.neededRam,
                                    warning.totalRam,
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.confirmLoadDespiteRamWarning() }) {
                                Text(stringResource(R.string.load_anyway))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissRamWarning() }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }

                modelLoadError?.let { message ->
                    AlertDialog(
                        onDismissRequest = { viewModel.consumeModelLoadError() },
                        title = { Text(stringResource(R.string.model_load_failed_title)) },
                        text = { Text(message) },
                        confirmButton = {
                            TextButton(onClick = { viewModel.consumeModelLoadError() }) {
                                Text(stringResource(R.string.model_load_failed_dismiss))
                            }
                        }
                    )
                }

                if (showParamsSheet) {
                    GenerationParamsSheet(
                        params = generationParams,
                        maxContextSize = maxContextSize,
                        supportsThinking = supportsThinking,
                        systemPrompt = systemPrompt,
                        canUpdateLinkedPrompt = systemPromptId != null,
                        onParamsChanged = { viewModel.updateGenerationParams(it) },
                        onUpdateLinkedPrompt = { viewModel.updateLinkedSystemPrompt(it) },
                        onSaveAsNewPrompt = { viewModel.createAndApplySystemPrompt(it) },
                        onClearSystemPrompt = { viewModel.clearSystemPrompt() },
                        onDismiss = { showParamsSheet = false }
                    )
                }

                val mainContent: @Composable () -> Unit = {
                    Scaffold(
                        topBar = {
                            Column {
                                ConversationBar(
                                    modelInfo = modelInfo,
                                    modelStatus = modelStatus,
                                    showNavIcon = !showPermanent,
                                    compact = showPermanent,
                                    onNavIconPressed = {
                                        scope.launch { drawerState.open() }
                                    },
                                    colors = topBarColors,
                                    onModelNamePressed = {
                                        viewModel.loadModelList()
                                    },
                                    onNewSessionPressed = {
                                        viewModel.newConversation()
                                    }
                                )
                                // Tablet: the surface-coloured top bar would
                                // otherwise blend into the chat pane background.
                                // Show the divider only when there's actually
                                // content scrolled behind the bar — at scroll
                                // offset 0 the bar is just bordering empty
                                // space and the line looks like dead chrome.
                                // Left inset keeps the line from touching the
                                // floating master card on the left.
                                if (showPermanent && isScrolled) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    )
                                }
                            }
                            if (models.isNotEmpty()) {
                                // Check if any models are downloaded
                                val hasDownloadedModels = models.any { it.isDownloaded }
                                if (hasDownloadedModels) {
                                    SelectModelDialog(
                                        models = models,
                                        isModelLoaded = modelInfo != null,
                                        // On tablet, center the dialog inside
                                        // the chat pane (right of the 320dp
                                        // permanent sidebar) instead of the
                                        // full window — otherwise it visually
                                        // covers the sessions list.
                                        // Match the sidebar width so the dialog
                                        // centers in the chat pane even when
                                        // the sidebar widened to align with a
                                        // foldable's hinge.
                                        chatPaneStartOffset = if (showPermanent) sidebarWidth else 0.dp,
                                        onLoadModel = { model ->
                                            viewModel.loadModel(model)
                                        },
                                        onUnloadModel = {
                                            viewModel.unloadModel()
                                        },
                                        onGenerationParams = {
                                            showParamsSheet = true
                                        },
                                        onBrowseModels = {
                                            // Guard against NavController throwing IllegalArgumentException
                                            // when the user double-taps or another navigation moved us
                                            // off nav_home before this callback fired.
                                            val nav = findNavController()
                                            if (nav.currentDestination?.id == R.id.nav_home) {
                                                nav.navigate(R.id.action_home_to_models)
                                            }
                                        },
                                        onDismissRequest = {
                                            viewModel.resetModelList()
                                        }
                                    )
                                } else {
                                    // No downloaded models - go directly to Models screen
                                    LaunchedEffect(Unit) {
                                        viewModel.resetModelList()
                                        if (findNavController().currentDestination?.id == R.id.nav_home) {
                                            findNavController().navigate(R.id.action_home_to_models)
                                        }
                                    }
                                }
                            } else if (modelReport != null) {
                                AlertDialog(
                                    onDismissRequest = {
                                        modelReport = null
                                    },
                                    title = {
                                        Text(text = stringResource(R.string.session_info))
                                    },
                                    text = {
                                        Text(
                                            text = modelReport!!,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { modelReport = null }) {
                                            Text(text = stringResource(R.string.close))
                                        }
                                    }
                                )
                            }
                        },
                        // Exclude ime and navigation bar padding so this can be added by the UserInput composable
                        contentWindowInsets = ScaffoldDefaults
                            .contentWindowInsets
                            .exclude(WindowInsets.navigationBars)
                            .exclude(WindowInsets.ime),
                        modifier = Modifier
                    ) { paddingValues ->
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                                .drawBehind {
                                    val strokeWidth = 2.dp.toPx()
                                    val x = size.width * progress
                                    drawLine(
                                        colorScheme.primary,
                                        start = Offset(0f, 0f),
                                        end = Offset(x, 0f),
                                        strokeWidth = strokeWidth
                                    )
                                }) {
                            if (modelInfo == null && messages.isEmpty()) {
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    WhatsNewText()
                                }
                            } else {
                                Messages(
                                    messages = messages,
                                    modifier = Modifier.weight(1f),
                                    scrollState = scrollState,
                                    isGenerating = isGenerating == true,
                                    sessionModelHint = sessionModelHint,
                                    onSessionModelHintClick = { filename ->
                                        viewModel.loadModelByFilename(filename)
                                    },
                                    onSessionModelHintDismiss = {
                                        viewModel.dismissSessionModelHint()
                                    },
                                    onTokenCountClicked = {
                                        modelReport = viewModel.getReport()
                                    }
                                )
                            }
                            // Picker sits just above the composer. Visible only when:
                            //   - model is ready
                            //   - chat is empty
                            //   - library has entries
                            //   - the session has no prompt selected yet
                            // On pick: the picker row handles its own flight
                            // animation per-card, then fires onPick — which flips
                            // the visibility gate. Exit uses ExitTransition.None
                            // so the row disappears immediately after the card
                            // finishes flying (no double-animation).
                            // On clear: the row slides back up and fades in.
                            val pickerVisible = isModelReady &&
                                messages.isEmpty() &&
                                recentSystemPrompts.isNotEmpty() &&
                                systemPrompt.isEmpty()
                            androidx.compose.animation.AnimatedVisibility(
                                visible = pickerVisible,
                                enter = androidx.compose.animation.fadeIn(
                                    animationSpec = androidx.compose.animation.core.tween(220)
                                ) + androidx.compose.animation.slideInVertically(
                                    animationSpec = androidx.compose.animation.core.tween(220),
                                    initialOffsetY = { it / 2 }
                                ),
                                exit = androidx.compose.animation.ExitTransition.None
                            ) {
                                SystemPromptPickerRow(
                                    prompts = recentSystemPrompts,
                                    selectedText = systemPrompt,
                                    onPick = { viewModel.applySystemPrompt(it.id, it.text) },
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            UserInput(
                                integrateWithSurface = showPermanent,
                                // Show the chat/input divider only when the
                                // list actually has content disappearing below
                                // the input dock. At the bottom of the chat
                                // (canScrollDown = false) the divider is
                                // bordering empty space and looks like noise.
                                showTopDivider = showPermanent && canScrollDown,
                                modifier = Modifier
                                    .navigationBarsPadding()
                                    .imePadding(),
                                focusRequester = inputFocusRequester,
                                status = if (modelInfo == null || !isModelReady)
                                    UserInputStatus.NOT_LOADED
                                else if (isGenerating == true)
                                    UserInputStatus.GENERATING
                                else
                                    UserInputStatus.IDLE,
                                supportsThinking = supportsThinking,
                                thinkingEnabled = thinkingEnabled,
                                onThinkingToggle = { viewModel.toggleThinking() },
                                onSwipeUp = {
                                    if (isModelReady) showParamsSheet = true
                                },
                                onMessageSent = { content ->
                                    viewModel.addMessage(
                                        Message("User", content)
                                    )
                                },
                                onCancelClicked = {
                                    viewModel.cancelGeneration()
                                },
                                // let this element handle the padding so that the elevation is shown behind the
                                // navigation bar
                                resetScroll = {
                                    scope.launch {
                                        val lastIndex = scrollState.layoutInfo.totalItemsCount - 1
                                        if (lastIndex >= 0) {
                                            scrollState.animateScrollToItem(lastIndex)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                if (showPermanent) {
                    PermanentNavigationDrawer(
                        drawerContent = {
                            PermanentSessionList(
                                sessions = sessions,
                                currentSessionId = currentSessionId,
                                width = sidebarWidth,
                                onSessionSelected = { sessionId ->
                                    viewModel.loadSession(sessionId)
                                },
                                onDeleteSession = { sessionId ->
                                    viewModel.deleteSession(sessionId)
                                },
                                onRenameSession = { sessionId, newTitle ->
                                    viewModel.renameSession(sessionId, newTitle)
                                },
                                onPinSession = { sessionId, pinned ->
                                    viewModel.pinSession(sessionId, pinned)
                                },
                                onSettingsClicked = {
                                    if (findNavController().currentDestination?.id == R.id.nav_home) {
                                        findNavController().navigate(R.id.action_home_to_settings)
                                    }
                                }
                            )
                        }
                    ) { mainContent() }
                } else {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            SessionListDrawer(
                                sessions = sessions,
                                currentSessionId = currentSessionId,
                                onSessionSelected = { sessionId ->
                                    viewModel.loadSession(sessionId)
                                    scope.launch { drawerState.close() }
                                },
                                onDeleteSession = { sessionId ->
                                    viewModel.deleteSession(sessionId)
                                },
                                onRenameSession = { sessionId, newTitle ->
                                    viewModel.renameSession(sessionId, newTitle)
                                },
                                onPinSession = { sessionId, pinned ->
                                    viewModel.pinSession(sessionId, pinned)
                                },
                                onSettingsClicked = {
                                    scope.launch {
                                        drawerState.close()
                                        if (findNavController().currentDestination?.id == R.id.nav_home) {
                                            findNavController().navigate(R.id.action_home_to_settings)
                                        }
                                    }
                                }
                            )
                        }
                    ) { mainContent() }
                }
            }
        }
    }
}
