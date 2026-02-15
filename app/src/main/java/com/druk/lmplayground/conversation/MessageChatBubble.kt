package com.druk.lmplayground.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.druk.lmplayground.R

private val ChatBubbleShape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 20.dp)

@Composable
fun ChatItemBubble(
    message: Message,
    isUserMe: Boolean
) {

    val backgroundBubbleColor = if (isUserMe) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    var showRatingSheet by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Column {
        Surface {
            val styledMessage = messageFormatter(
                text = message.content,
                primary = isUserMe
            )

            SelectionContainer {
                Text(
                    text = styledMessage,
                    style = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current)
                )
            }
        }

        message.image?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                color = backgroundBubbleColor,
                shape = ChatBubbleShape
            ) {
                Image(
                    painter = painterResource(it),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(160.dp),
                    contentDescription = stringResource(id = R.string.attached_image)
                )
            }
        }

        if (!isUserMe) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(stripThinkTags(message.content)))
                }) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(id = R.string.copy),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = { /* upvote - no-op */ }) {
                    Icon(
                        imageVector = Icons.Outlined.ThumbUp,
                        contentDescription = stringResource(id = R.string.upvote),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = { showRatingSheet = true }) {
                    Icon(
                        imageVector = Icons.Outlined.ThumbDown,
                        contentDescription = stringResource(id = R.string.downvote),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }

    if (showRatingSheet) {
        RatingBottomSheet(onDismiss = { showRatingSheet = false })
    }
}