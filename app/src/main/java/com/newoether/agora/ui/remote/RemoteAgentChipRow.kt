package com.newoether.agora.ui.remote

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.remote.RemoteDeviceStatus

@Composable
internal fun RemoteAgentChipRow(status: RemoteDeviceStatus, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val statusLabel = stringResource(when (status) {
        RemoteDeviceStatus.CONNECTED -> R.string.remote_online
        RemoteDeviceStatus.CONNECTING -> R.string.remote_connecting
        else -> R.string.remote_offline
    })
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.clip(CircleShape)
                .indication(interactionSource, ripple())
                .pointerInput(interactionSource) {
                    // This informational chip has press feedback but no activation or network action.
                    detectTapGestures(onPress = { position ->
                        val press = PressInteraction.Press(position)
                        interactionSource.emit(press)
                        val end = if (tryAwaitRelease()) PressInteraction.Release(press)
                            else PressInteraction.Cancel(press)
                        interactionSource.emit(end)
                    })
                }
                .testTag("remote-agent-chip"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Row(
                modifier = Modifier.height(40.dp).padding(horizontal = 12.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Codex Desktop, $statusLabel" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(R.drawable.provider_openai), null, Modifier.size(18.dp))
                Text("Codex Desktop", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Crossfade(targetState = status, animationSpec = tween(200), label = "remoteAgentStatus") { value ->
                    val color = when (value) {
                        RemoteDeviceStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                        RemoteDeviceStatus.CONNECTING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    }
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                }
            }
        }
    }
}
