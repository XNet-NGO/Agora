package com.newoether.agora.ui.remote

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.remote.remoteDeviceName

/** Product and configured device identity share chat's existing top/composer range. */
@Composable
internal fun RemoteEmptyConversation(
    state: RemoteState,
    active: Boolean,
    topBarHeight: Dp,
    bottomBarHeight: Dp,
    onRetry: () -> Unit,
) {
    val hint = state.remoteEmptyConversationHint() ?: return
    val normalWelcome = hint == R.string.remote_new_chat_hint || hint == R.string.remote_empty_conversation_hint ||
        hint == R.string.remote_empty_connecting_hint
    val headline = if (normalWelcome) remoteSourceName(state.selectedAdapter) else stringResource(hint)
    val deviceName = remoteDeviceName(state.devices.firstOrNull { it.id == state.deviceId }?.name.orEmpty())
        .ifEmpty { stringResource(R.string.remote_device) }
    val retryVisible = state.canRetryEmptyConnection()
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(top = topBarHeight, bottom = bottomBarHeight),
    ) {
        val availableHeight = maxHeight
        val scrollState = rememberScrollState()
        Layout(
            modifier = Modifier.align(Alignment.Center)
                .widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 24.dp)
                .testTag("remote-empty-conversation")
                .verticalScroll(scrollState, overscrollEffect = null),
            content = {
                Icon(
                    painter = painterResource(remoteSourceIcon(state.selectedAdapter)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.testTag("remote-welcome-mark"),
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = headline,
                        modifier = Modifier.semantics { heading() }.testTag("remote-welcome-headline"),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, lineHeight = 35.sp),
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = deviceName,
                        modifier = Modifier.testTag("remote-welcome-device"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!state.canSend) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.remote_source_read_only), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    if (state.historyBounded) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(remoteHistoryBoundaryLabel(state)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    if (retryVisible) {
                        Spacer(Modifier.height(16.dp))
                        TextButton(enabled = active && !state.loading, onClick = onRetry) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            // Preserve natural text and action height inside the bounded scroll viewport.
            val details = measurables.last().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val markSize = minOf(80.dp.roundToPx(),
                (availableHeight.roundToPx() - details.height - 16.dp.roundToPx()).coerceAtLeast(0))
            val mark = measurables.first().measure(Constraints.fixed(markSize, markSize))
            val markGap = if (markSize > 0) 16.dp.roundToPx() else 0
            layout(constraints.maxWidth, markSize + markGap + details.height) {
                mark.placeRelative((constraints.maxWidth - markSize) / 2, 0)
                details.placeRelative((constraints.maxWidth - details.width) / 2, markSize + markGap)
            }
        }
    }
}
