package com.newoether.agora.ui.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.newoether.agora.remote.RemoteAdapter
import com.newoether.agora.remote.RemoteCapabilities
import com.newoether.agora.remote.RemoteDevice
import com.newoether.agora.remote.RemoteDeviceStatus
import com.newoether.agora.remote.RemoteRuntime
import com.newoether.agora.remote.RemoteSession
import com.newoether.agora.remote.RemoteSessionAccess
import com.newoether.agora.remote.RemoteState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h700dp-mdpi")
class RemoteSourcePresentationTest {
    @get:Rule val compose = createComposeRule()
    private val desktop = RemoteAdapter("codex-desktop", "codex", "desktop", "connected")
    private val claude = RemoteAdapter("claude-code", "claude", "cli", "disconnected")

    @Test fun offlineSourceRemainsSelectableAndFreshRemovalRemovesOnlyItsChip() {
        var sources by mutableStateOf(listOf(desktop, claude))
        var selected by mutableStateOf(desktop.id)
        var selections = 0
        compose.setContent { MaterialTheme {
            RemoteAgentChipRow(sources, selected, { selected = it; selections++ })
        } }
        val target = compose.onNodeWithTag("remote-agent-chip-claude-code")
        val bounds = target.getUnclippedBoundsInRoot()
        assertEquals(40f, (bounds.bottom - bounds.top).value, 0.01f)
        target.performClick().assertIsSelected()
        assertEquals(claude.id, selected)
        assertEquals(1, selections)
        target.performClick()
        assertEquals(1, selections)
        compose.runOnIdle { sources = listOf(desktop) }
        compose.onNodeWithTag("remote-agent-chip-claude-code").assertDoesNotExist()
        compose.onNodeWithText("Codex Desktop").assertExists()
        assertEquals(claude.id, selected)
    }

    @Test fun claudeWelcomeUsesItsIdentityWithoutCodexModelOrCompleteHistoryClaims() {
        val source = claude.copy(state = "connected", nativeScopeId = "claude-scope",
            capabilities = RemoteCapabilities(history = true, historyMode = "bounded"))
        val state = RemoteState(deviceId = "device", restoring = false, hydrationEnabled = true,
            devices = listOf(RemoteDevice("device", "Computer", "address", RemoteDeviceStatus.CONNECTED,
                adapters = listOf(source), multiAdapter = true)),
            session = RemoteSession("native", "", "", 1, access = RemoteSessionAccess(
                "claude-scope", "native", "binding", source.capabilities)), runtime = RemoteRuntime("idle"))
        compose.setContent { MaterialTheme {
            RemoteEmptyConversation(state, true, 64.dp, 180.dp, {})
        } }
        compose.onNodeWithTag("remote-welcome-headline").assertExists()
        compose.onNodeWithText("Claude Code").assertExists()
        compose.onNodeWithText("Computer").assertExists()
        compose.onNodeWithText("Codex Desktop").assertDoesNotExist()
        compose.onNodeWithText("Shows only text captured after the Claude Mod registered. Earlier history, tool activity, media, and thinking are not included.").assertExists()
        assertFalse(state.canSend)
        assertTrue(state.historyBounded)
    }
}
