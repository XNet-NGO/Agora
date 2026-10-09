package com.newoether.agora.data.repository

import com.newoether.agora.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Enablement toggles for the agent tools ported from AIOPE (todo, location, introspect,
 * network scan, file server). Held in a small dedicated class — rather than inline on
 * [SettingsRepository] — so the repository stays within the project's 800-line Kotlin budget.
 *
 * Backed by the same DataStore keys as the rest of the settings surface; read/write via
 * [SettingsManager]. All default to disabled.
 */
class AgentToolSettings(
    private val settingsManager: SettingsManager,
    scope: CoroutineScope,
) {
    val todoEnabled: StateFlow<Boolean> = settingsManager.todoEnabled.stateIn(scope, SharingStarted.Eagerly, false)
    val locationEnabled: StateFlow<Boolean> = settingsManager.locationEnabled.stateIn(scope, SharingStarted.Eagerly, false)
    val introspectEnabled: StateFlow<Boolean> = settingsManager.introspectEnabled.stateIn(scope, SharingStarted.Eagerly, false)
    val networkScanEnabled: StateFlow<Boolean> = settingsManager.networkScanEnabled.stateIn(scope, SharingStarted.Eagerly, false)
    val fileServerEnabled: StateFlow<Boolean> = settingsManager.fileServerEnabled.stateIn(scope, SharingStarted.Eagerly, false)

    fun setTodoEnabled(enabled: Boolean) = scope.launch { settingsManager.saveTodoEnabled(enabled) }
    fun setLocationEnabled(enabled: Boolean) = scope.launch { settingsManager.saveLocationEnabled(enabled) }
    fun setIntrospectEnabled(enabled: Boolean) = scope.launch { settingsManager.saveIntrospectEnabled(enabled) }
    fun setNetworkScanEnabled(enabled: Boolean) = scope.launch { settingsManager.saveNetworkScanEnabled(enabled) }
    fun setFileServerEnabled(enabled: Boolean) = scope.launch { settingsManager.saveFileServerEnabled(enabled) }
}