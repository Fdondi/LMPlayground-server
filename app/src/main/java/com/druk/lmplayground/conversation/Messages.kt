package com.druk.lmplayground.conversation

import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Messages(
    messages: List<Message>,
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
    isGenerating: Boolean = false,
    sessionModelHint: Pair<String, String>? = null,
    onSessionModelHintClick: ((String) -> Unit)? = null,
    onSessionModelHintDismiss: (() -> Unit)? = null,
    onTokenCountClicked: (() -> Unit)? = null,
    onImageClick: (Uri) -> Unit = {}
) {
    var piningBottom by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        piningBottom = true
    }
    Box(modifier = modifier.pointerInput(Unit) {
        detectTapGestures(onPress = {
            piningBottom = false
        })
    }) {
        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = messages,
                key = { _, msg -> msg.id }
            ) { index, content ->
                val isLast = index == messages.lastIndex
                Message(
                    msg = content,
                    isUserMe = content.author == "User",
                    showActions = !(isLast && isGenerating),
                    onTokenCountClicked = onTokenCountClicked,
                    onImageClick = onImageClick
                )
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Show the button if the first visible item is not the first one or if the offset is
        // greater than the threshold.
        val jumpToBottomButtonEnabled by remember {
            derivedStateOf {
                scrollState.canScrollForward
            }
        }

        // Auto scrolling to the latest item
        LaunchedEffect(piningBottom, scrollState.canScrollForward) {
            if (scrollState.canScrollForward && piningBottom) {
                scope.launch {
                    val totalItemsCount = scrollState.layoutInfo.totalItemsCount
                    scrollState.scrollToItem(totalItemsCount - 1)
                }
            }
        }

        if (sessionModelHint != null) {
            // Model hint FAB — same style as JumpToBottom
            LaunchedEffect(sessionModelHint) {
                delay(3000)
                onSessionModelHintDismiss?.invoke()
            }
            ExtendedFloatingActionButton(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        modifier = Modifier.height(18.dp),
                        contentDescription = null
                    )
                },
                text = {
                    Text(text = "Previously used: ${sessionModelHint.first}")
                },
                onClick = { onSessionModelHintClick?.invoke(sessionModelHint.second) },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(x = 0.dp, y = (-32).dp)
                    .height(36.dp)
            )
        } else {
            JumpToBottom(
                // Only show if the scroller is not at the bottom
                enabled = jumpToBottomButtonEnabled,
                onClicked = {
                    scope.launch {
                        val totalItemsCount = scrollState.layoutInfo.totalItemsCount
                        scrollState.animateScrollToItem(totalItemsCount - 1)
                        piningBottom = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
