package com.newoether.agora.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.remote.RemoteAdapter

@Composable
internal fun remoteSourceName(source: RemoteAdapter): String = stringResource(when (source.id) {
    "codex-cli" -> R.string.remote_source_codex_cli
    "claude-code" -> R.string.remote_source_claude_code
    "codex-desktop", RemoteAdapter.LEGACY_DESKTOP -> R.string.remote_source_codex_desktop
    else -> R.string.remote_source_unavailable
})

internal fun remoteHistoryBoundaryLabel(state: com.newoether.agora.remote.RemoteState): Int =
    if (state.selectedAdapter.id == "claude-code") R.string.remote_history_observed
    else R.string.remote_history_bounded

internal fun remoteSourceIcon(source: RemoteAdapter): Int =
    if (source.family == "claude") R.drawable.provider_anthropic else R.drawable.provider_openai
