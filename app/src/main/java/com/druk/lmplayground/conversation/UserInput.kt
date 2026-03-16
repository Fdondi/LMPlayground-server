package com.druk.lmplayground.conversation

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.druk.lmplayground.R

enum class UserInputStatus {
    IDLE,
    NOT_LOADED,
    GENERATING
}

@Preview
@Composable
fun UserInputPreview() {
    UserInput(onMessageSent = {})
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserInput(
    modifier: Modifier = Modifier,
    status: UserInputStatus = UserInputStatus.IDLE,
    focusRequester: FocusRequester = remember { FocusRequester() },
    supportsThinking: Boolean = false,
    thinkingEnabled: Boolean = true,
    onThinkingToggle: () -> Unit = {},
    supportsVision: Boolean = false,
    attachedImageUri: Uri? = null,
    onAttachImage: () -> Unit = {},
    onClearImage: () -> Unit = {},
    onSwipeUp: () -> Unit = {},
    onMessageSent: (String) -> Unit,
    onCancelClicked: () -> Unit = {},
    resetScroll: () -> Unit = {},
) {

    var textState by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    // Used to decide if the keyboard should be shown
    var textFieldFocusState by remember { mutableStateOf(false) }

    var dragAccumulator by remember { mutableStateOf(0f) }
    val swipeThreshold = -150f // negative = upward
    val draggableState = rememberDraggableState { delta ->
        dragAccumulator += delta
    }

    Surface(tonalElevation = 2.dp, contentColor = MaterialTheme.colorScheme.secondary) {
        Column(
            modifier = modifier.draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStarted = { dragAccumulator = 0f },
                onDragStopped = {
                    if (dragAccumulator < swipeThreshold) {
                        onSwipeUp()
                    }
                    dragAccumulator = 0f
                }
            )
        ) {
            // Image preview row
            if (attachedImageUri != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 4.dp,
                        modifier = Modifier.size(64.dp)
                    ) {
                        AsyncImage(
                            model = attachedImageUri,
                            contentDescription = "Attached image",
                            modifier = Modifier.size(64.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onClearImage, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove image",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            UserInputText(
                status,
                focusRequester = focusRequester,
                supportsThinking = supportsThinking,
                thinkingEnabled = thinkingEnabled,
                onThinkingToggle = onThinkingToggle,
                supportsVision = supportsVision,
                onAttachImage = onAttachImage,
                textFieldValue = textState,
                onTextChanged = { textState = it },
                // Only show the keyboard if there's no input selector and text field has focus
                keyboardShown = textFieldFocusState,
                // Close extended selector if text field receives focus
                onTextFieldFocused = { focused ->
                    if (focused) {
                        resetScroll()
                    }
                    textFieldFocusState = focused
                },
                sendMessageEnabled = textState.text.isNotBlank(),
                onMessageSent = {
                    onMessageSent(textState.text)
                    // Reset text field and close keyboard
                    textState = TextFieldValue()
                    // Move scroll to bottom
                    resetScroll()
                },
                onCancelClicked = onCancelClicked
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
fun Modifier.clearFocusOnKeyboardDismiss(): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    var keyboardAppearedSinceLastFocused by remember { mutableStateOf(false) }
    if (isFocused) {
        val imeIsVisible = WindowInsets.isImeVisible
        val focusManager = LocalFocusManager.current
        LaunchedEffect(imeIsVisible) {
            if (imeIsVisible) {
                keyboardAppearedSinceLastFocused = true
            } else if (keyboardAppearedSinceLastFocused) {
                focusManager.clearFocus()
            }
        }
    }
    onFocusEvent {
        if (isFocused != it.isFocused) {
            isFocused = it.isFocused
            if (isFocused) {
                keyboardAppearedSinceLastFocused = false
            }
        }
    }
}

val KeyboardShownKey = SemanticsPropertyKey<Boolean>("KeyboardShownKey")
var SemanticsPropertyReceiver.keyboardShownProperty by KeyboardShownKey

@ExperimentalFoundationApi
@Composable
private fun UserInputText(
    status: UserInputStatus,
    focusRequester: FocusRequester,
    supportsThinking: Boolean = false,
    thinkingEnabled: Boolean = true,
    onThinkingToggle: () -> Unit = {},
    supportsVision: Boolean = false,
    onAttachImage: () -> Unit = {},
    keyboardType: KeyboardType = KeyboardType.Text,
    onTextChanged: (TextFieldValue) -> Unit,
    textFieldValue: TextFieldValue,
    keyboardShown: Boolean,
    onTextFieldFocused: (Boolean) -> Unit,
    sendMessageEnabled: Boolean,
    onMessageSent: () -> Unit,
    onCancelClicked: () -> Unit
) {
    val a11ylabel = stringResource(id = R.string.textfield_desc)
    val hasLeftButtons = supportsThinking || supportsVision
    val textStartPadding = if (hasLeftButtons) 4.dp else 32.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .heightIn(min = 48.dp, max = 320.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (supportsVision) {
            val isDisabled = status == UserInputStatus.GENERATING
            IconButton(
                onClick = onAttachImage,
                enabled = !isDisabled,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = "Attach image",
                    modifier = if (isDisabled) Modifier.alpha(0.8f) else Modifier,
                    tint = LocalContentColor.current
                )
            }
        }
        if (supportsThinking) {
            val isDisabled = status == UserInputStatus.GENERATING
            IconButton(
                onClick = onThinkingToggle,
                enabled = !isDisabled,
                modifier = Modifier.padding(start = if (supportsVision) 0.dp else 4.dp)
            ) {
                Icon(
                    imageVector = if (thinkingEnabled) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                    contentDescription = if (thinkingEnabled) "Disable thinking" else "Enable thinking",
                    modifier = if (isDisabled) Modifier.alpha(0.8f) else Modifier,
                    tint = LocalContentColor.current
                )
            }
        }

        Box(Modifier.weight(1f)) {
            UserInputTextField(
                true,
                focusRequester,
                textFieldValue,
                onTextChanged,
                onTextFieldFocused,
                keyboardType,
                textStartPadding,
                Modifier.semantics {
                    contentDescription = a11ylabel
                    keyboardShownProperty = keyboardShown
                }
            )
        }

        val border = if (!sendMessageEnabled) {
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        } else {
            null
        }

        val disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

        val buttonColors = ButtonDefaults.buttonColors(
            disabledContainerColor = Color.Transparent,
            disabledContentColor = disabledContentColor
        )

        // Send button
        Box {
            when (status) {
                UserInputStatus.IDLE, UserInputStatus.NOT_LOADED -> {
                    Button(
                        modifier = Modifier.align(Alignment.Center).padding(end = 8.dp),
                        enabled = sendMessageEnabled && status == UserInputStatus.IDLE,
                        onClick = onMessageSent,
                        colors = buttonColors,
                        border = border,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            stringResource(id = R.string.send),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
                UserInputStatus.GENERATING -> {
                    Button(
                        modifier = Modifier.align(Alignment.Center).padding(end = 8.dp),
                        onClick = onCancelClicked,
                        colors = buttonColors,
                        border = border,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            stringResource(id = R.string.stop),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.UserInputTextField(
    isEnabled: Boolean,
    focusRequester: FocusRequester,
    textFieldValue: TextFieldValue,
    onTextChanged: (TextFieldValue) -> Unit,
    onTextFieldFocused: (Boolean) -> Unit,
    keyboardType: KeyboardType,
    startPadding: Dp,
    modifier: Modifier = Modifier
) {
    var lastFocusState by remember { mutableStateOf(false) }
    BasicTextField(
        value = textFieldValue,
        onValueChange = { onTextChanged(it) },
        modifier = modifier
            .padding(start = startPadding)
            .align(Alignment.CenterStart)
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .clearFocusOnKeyboardDismiss()
            .onFocusChanged { state ->
                if (lastFocusState != state.isFocused) {
                    onTextFieldFocused(state.isFocused)
                }
                lastFocusState = state.isFocused
            },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.None
        ),
        enabled = isEnabled,
        maxLines = 4,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurfaceVariant),
        textStyle = LocalTextStyle.current.copy(color = LocalContentColor.current)
    )

    val disableContentColor =
        MaterialTheme.colorScheme.onSurfaceVariant
    if (textFieldValue.text.isEmpty()) {
        Text(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = startPadding),
            text = stringResource(R.string.textfield_hint),
            style = MaterialTheme.typography.bodyLarge.copy(color = disableContentColor)
        )
    }
}
