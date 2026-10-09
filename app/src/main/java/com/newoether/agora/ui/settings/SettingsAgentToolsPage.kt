package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * Settings for the agent tools ported from AIOPE (see port-aiope-tools branch): todo list,
 * location, introspect (self-knowledge), network scanner, and file server. Each is a toggle
 * backed by a DataStore setting; all default to disabled.
 */
@Composable
fun SettingsAgentToolsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val todoEnabled by viewModel.settings.agentTools.todoEnabled.collectAsState()
    val locationEnabled by viewModel.settings.agentTools.locationEnabled.collectAsState()
    val introspectEnabled by viewModel.settings.agentTools.introspectEnabled.collectAsState()
    val networkScanEnabled by viewModel.settings.agentTools.networkScanEnabled.collectAsState()
    val fileServerEnabled by viewModel.settings.agentTools.fileServerEnabled.collectAsState()
    val scrollState = rememberScrollState()
    CollapsingSettingsScaffold(
        title = stringResource(R.string.agent_tools_title),
        onBack = onBack,
        scrollState = scrollState,
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.agent_tools_title),
                items = buildList {
                    add {
                        AgentToolToggle(
                            icon = Icons.Default.Checklist,
                            title = stringResource(R.string.agent_tools_todo),
                            description = stringResource(R.string.agent_tools_todo_desc),
                            checked = todoEnabled,
                            onCheckedChange = viewModel.settings.agentTools::setTodoEnabled,
                        )
                    }
                    add {
                        AgentToolToggle(
                            icon = Icons.Default.LocationOn,
                            title = stringResource(R.string.agent_tools_location),
                            description = stringResource(R.string.agent_tools_location_desc),
                            checked = locationEnabled,
                            onCheckedChange = viewModel.settings.agentTools::setLocationEnabled,
                        )
                    }
                    add {
                        AgentToolToggle(
                            icon = Icons.Default.Info,
                            title = stringResource(R.string.agent_tools_introspect),
                            description = stringResource(R.string.agent_tools_introspect_desc),
                            checked = introspectEnabled,
                            onCheckedChange = viewModel.settings.agentTools::setIntrospectEnabled,
                        )
                    }
                    add {
                        AgentToolToggle(
                            icon = Icons.Default.Language,
                            title = stringResource(R.string.agent_tools_network_scan),
                            description = stringResource(R.string.agent_tools_network_scan_desc),
                            checked = networkScanEnabled,
                            onCheckedChange = viewModel.settings.agentTools::setNetworkScanEnabled,
                        )
                    }
                    add {
                        AgentToolToggle(
                            icon = Icons.Default.Storage,
                            title = stringResource(R.string.agent_tools_file_server),
                            description = stringResource(R.string.agent_tools_file_server_desc),
                            checked = fileServerEnabled,
                            onCheckedChange = viewModel.settings.agentTools::setFileServerEnabled,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun AgentToolToggle(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    )
}