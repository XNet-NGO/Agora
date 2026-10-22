package com.newoether.agora.ui.remote

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.remote.RemoteAdapter
import com.newoether.agora.remote.RemoteDeviceStatus
import com.newoether.agora.ui.settings.McpStatusDot

/** Configured sources remain selectable when unavailable; selection never dispatches native work. */
@Composable
internal fun RemoteAgentChipRow(
    sources: List<RemoteAdapter>, selectedId: String, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    Row(modifier.horizontalScroll(rememberScrollState()).padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.forEach { source ->
            val selected = source.id == selectedId
            val name = remoteSourceName(source)
            val status = stringResource(remoteConnectionStatusLabel(source.status.toMcpConnectionStatus()))
            Surface(
                modifier = Modifier.clip(CircleShape).selectable(selected, enabled = enabled, role = Role.Tab,
                    onClick = { if (!selected) onSelect(source.id) }).testTag("remote-agent-chip-${source.id}"),
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(Modifier.height(40.dp).padding(horizontal = 12.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "$name, $status" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(remoteSourceIcon(source)), null, Modifier.size(18.dp))
                    Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Crossfade(source.status.toMcpConnectionStatus(), animationSpec = tween(200), label = "remoteAgentStatus") { value ->
                        Box(Modifier.clearAndSetSemantics { testTag = "remote-agent-status-dot" }) { McpStatusDot(value) }
                    }
                }
            }
        }
    }
}

/** Legacy discovery has a single informational source with the original press feedback. */
@Composable
internal fun RemoteAgentChipRow(status: RemoteDeviceStatus, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val name = stringResource(R.string.remote_source_codex_desktop)
    val label = stringResource(remoteConnectionStatusLabel(status.toMcpConnectionStatus()))
    Row(modifier.horizontalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Surface(
            modifier = Modifier.clip(CircleShape).indication(interactions, ripple())
                .pointerInput(interactions) {
                    detectTapGestures(onPress = { position ->
                        val press = PressInteraction.Press(position)
                        interactions.emit(press)
                        interactions.emit(if (tryAwaitRelease()) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                    })
                }.testTag("remote-agent-chip"),
            shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Row(Modifier.height(40.dp).padding(horizontal = 12.dp)
                .semantics(mergeDescendants = true) { contentDescription = "$name, $label" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.provider_openai), null, Modifier.size(18.dp))
                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Crossfade(status.toMcpConnectionStatus(), animationSpec = tween(200), label = "remoteAgentStatus") { value ->
                    Box(Modifier.clearAndSetSemantics { testTag = "remote-agent-status-dot" }) { McpStatusDot(value) }
                }
            }
        }
    }
}
