package com.newoether.agora.ui.chat.interaction

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.SubagentSummary
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator

internal fun subagentStatusIcon(status: String): ImageVector = when (status) {
    "failed" -> Icons.Default.ErrorOutline
    "stopped" -> Icons.Default.PauseCircleOutline
    else -> Icons.Default.AccountTree
}

@Composable
internal fun subagentStatusLabel(status: String): String = stringResource(when (status) {
    "running" -> R.string.subagent_running
    "completed" -> R.string.subagent_completed
    "failed" -> R.string.subagent_failed
    "stopped" -> R.string.subagent_stopped
    else -> R.string.subagent_idle
})

/** Navigation only; the shared tools row owns placement, scrolling and outgoing retention. */
@Composable
internal fun ConversationSubagentCapsule(
    agent: SubagentSummary,
    selected: Boolean,
    active: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubagentNavigationCapsule(
        title = agent.title, status = agent.status, selected = selected, active = active,
        identity = agent.id, onClick = { onSelect(agent.id) }, modifier = modifier,
    )
}

/** Both single and aggregate content use the same surface and color/status animation owners. */
@Composable
internal fun SubagentNavigationCapsule(
    title: String,
    status: String,
    selected: Boolean,
    active: Boolean,
    identity: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(250, easing = LinearEasing),
        label = "subagent-container:$identity",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(250, easing = LinearEasing),
        label = "subagent-content:$identity",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(250, easing = LinearEasing),
        label = "subagent-label:$identity",
    )
    Surface(
        onClick = { if (active) onClick() }, enabled = active,
        shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier.semantics { this.selected = selected },
    ) {
        Row(
            Modifier.height(48.dp).widthIn(max = 240.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubagentStatus(status, "subagent-status:$identity")
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    stringResource(R.string.message_source_subagent),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Crossfade(
                    targetState = title,
                    animationSpec = tween(250, easing = LinearEasing),
                    label = "subagent-title:$identity",
                ) { text ->
                    Text(
                        text, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SubagentStatus(status: String, tag: String) {
    Crossfade(
        targetState = status,
        modifier = Modifier.size(18.dp).testTag(tag),
        animationSpec = tween(250, easing = LinearEasing),
        label = tag,
    ) { value ->
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (value == "running") {
                MotionAwareCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(subagentStatusIcon(value), subagentStatusLabel(value), Modifier.size(18.dp))
            }
        }
    }
}
