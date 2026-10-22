package com.newoether.agora.ui.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator

/** Shared import presentation; file ownership and picker admission stay with each editor. */
@Composable
internal fun ModelImportItem(
    title: String,
    description: String,
    icon: ImageVector,
    importing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    SettingsItem(
        modifier = modifier.clickable(enabled = enabled && !importing, role = Role.Button, onClick = onClick),
        headlineContent = {
            Crossfade(
                targetState = title,
                animationSpec = tween(250),
                label = "model-import-title",
            ) { Text(it, color = primary) }
        },
        supportingContent = { Text(description) },
        leadingContent = {
            Crossfade(
                targetState = importing,
                modifier = Modifier.size(24.dp).testTag("model-import-leading"),
                animationSpec = tween(250),
                label = "model-import-leading",
            ) { busy ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (busy) {
                        MotionAwareCircularProgressIndicator(
                            modifier = Modifier.size(24.dp).testTag("model-import-progress"),
                            color = primary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(icon, null, modifier = Modifier.size(24.dp), tint = primary)
                    }
                }
            }
        },
        trailingContent = trailingContent,
    )
}
