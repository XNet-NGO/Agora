package com.newoether.agora.ui.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.remote.RemoteDevice
import com.newoether.agora.remote.RemoteSession
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.remote.RemoteViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en")
class RemoteSessionMenuTest {
    @get:Rule val compose = createComposeRule()
    private val session = RemoteSession("session", "Native Task", "/native", 1)
    private val state = MutableStateFlow(RemoteState(
        restoring = false,
        deviceId = "device",
        devices = listOf(RemoteDevice("device", "Workstation", "http://localhost")),
        sessions = listOf(session),
    ))
    private val vm = mockk<RemoteViewModel>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private var active by mutableStateOf(true)
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun showScreen() {
        every { vm.state } returns state
        compose.setContent {
            MaterialTheme {
                RemoteScreen(vm, settings, active, {}, {}, { _, _ -> }, { _, _, _ -> })
            }
        }
    }

    @Test fun pendingMutationKeepsMenuAndReadNavigationAvailableWithoutAdmittingAnotherMutation() {
        showScreen()
        compose.onNodeWithText("Workstation").assertExists()
        compose.onNodeWithText("Codex Desktop").assertExists()
        compose.onNodeWithText(text(R.string.remote_sessions)).assertExists()
        compose.runOnIdle { state.value = state.value.copy(controlling = true) }
        compose.onNodeWithContentDescription(text(R.string.more)).assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.rename)).assertExists().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.remote_archive)).assertExists().assertIsNotEnabled()
        verify(exactly = 0) { vm.renameSession(any(), any()) }
        verify(exactly = 0) { vm.archiveSession(any()) }

        compose.runOnIdle { state.value = state.value.copy(controlling = false) }
        compose.onNodeWithText(text(R.string.rename)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.remote_archive)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.rename)).performClick()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.runOnIdle { state.value = state.value.copy(controlling = true) }
        compose.onNodeWithText(session.title).assertIsEnabled().performClick()
        verify(exactly = 1) { vm.selectSession(session) }
    }

    @Test fun pendingMutationKeepsUsageAvailableWhileHiddenPageRejectsMenuInput() {
        state.value = state.value.copy(controlling = true)
        showScreen()
        compose.onNodeWithContentDescription(text(R.string.remote_usage)).assertIsEnabled()
        compose.onNodeWithContentDescription(text(R.string.more)).assertIsEnabled()
        compose.runOnIdle { active = false }
        compose.onNodeWithContentDescription(text(R.string.more)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.remote_usage)).assertIsNotEnabled()
        verify(exactly = 0) { vm.renameSession(any(), any()) }
        verify(exactly = 0) { vm.archiveSession(any()) }
    }
}
