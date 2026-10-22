package com.newoether.agora.ui.remote

import androidx.annotation.StringRes
import com.newoether.agora.R
import com.newoether.agora.mcp.McpConnectionStatus
import com.newoether.agora.remote.RemoteDeviceStatus
import com.newoether.agora.remote.RemoteState

internal fun RemoteDeviceStatus.toMcpConnectionStatus(): McpConnectionStatus = when (this) {
    RemoteDeviceStatus.IDLE -> McpConnectionStatus.IDLE
    RemoteDeviceStatus.CONNECTING -> McpConnectionStatus.CONNECTING
    RemoteDeviceStatus.CONNECTED -> McpConnectionStatus.CONNECTED
    RemoteDeviceStatus.ERROR -> McpConnectionStatus.ERROR
}

@StringRes
internal fun remoteConnectionStatusLabel(status: McpConnectionStatus): Int = when (status) {
    McpConnectionStatus.CONNECTED -> R.string.remote_online
    McpConnectionStatus.CONNECTING -> R.string.remote_connecting
    McpConnectionStatus.IDLE, McpConnectionStatus.ERROR -> R.string.remote_offline
}

/** Display native Desktop availability; gateway failure can only remove readiness. */
internal fun RemoteState.remoteConnectionStatus(): McpConnectionStatus {
    val device = devices.firstOrNull { it.id == deviceId } ?: return McpConnectionStatus.IDLE
    return (if (device.status == RemoteDeviceStatus.CONNECTED) selectedAdapter.status else device.status)
        .toMcpConnectionStatus()
}

/** Empty guidance never replaces an admitted message, including during read failure or recovery. */
@StringRes
internal fun RemoteState.remoteEmptyConversationHint(): Int? {
    if (session == null || messageGroups.isNotEmpty()) return null
    val connection = remoteConnectionStatus()
    return when {
        !selectedAdapter.legacy && (error || connection == McpConnectionStatus.IDLE ||
            connection == McpConnectionStatus.ERROR) -> R.string.remote_source_unavailable
        error -> R.string.remote_empty_retry_hint
        loading || connection == McpConnectionStatus.CONNECTING -> R.string.remote_empty_connecting_hint
        isDraft && connection == McpConnectionStatus.CONNECTED -> R.string.remote_new_chat_hint
        connection != McpConnectionStatus.CONNECTED -> R.string.remote_empty_offline_hint
        !hydrationEnabled -> R.string.remote_empty_connecting_hint
        else -> R.string.remote_empty_conversation_hint
    }
}

internal fun RemoteState.canRetryEmptyConnection(): Boolean =
    remoteEmptyConversationHint() != null && (error ||
        !loading && remoteConnectionStatus() != McpConnectionStatus.CONNECTING &&
        remoteConnectionStatus() != McpConnectionStatus.CONNECTED)
