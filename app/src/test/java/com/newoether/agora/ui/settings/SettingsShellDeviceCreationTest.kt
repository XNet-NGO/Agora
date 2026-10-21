package com.newoether.agora.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.ChatViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
class SettingsShellDeviceCreationTest {
    @get:Rule val compose = createComposeRule()

    private val settings = mockk<SettingsRepository>()
    private val viewModel = mockk<ChatViewModel>()
    private val devices = MutableStateFlow<List<ShellDeviceConfig>>(emptyList())
    private val additions = mutableListOf<ShellDeviceConfig>()
    private val updates = mutableListOf<ShellDeviceConfig>()
    private val results = mutableListOf<(Boolean) -> Unit>()
    private var pageVisible by mutableStateOf(true)
    private var backCalls = 0
    private lateinit var strings: Map<Int, String>

    private fun showPage(existing: List<ShellDeviceConfig> = emptyList()) {
        devices.value = existing
        every { viewModel.settings } returns settings
        every { viewModel.isSandboxFlavor } returns false
        every { settings.shellEnabled } returns MutableStateFlow(true)
        every { settings.shellConfirmEnabled } returns MutableStateFlow(true)
        every { settings.shellDevices } returns devices
        every { settings.sandboxEnabled } returns MutableStateFlow(false)
        every { settings.sandboxSharedStorageEnabled } returns MutableStateFlow(false)
        every { settings.showDocumentationFab } returns MutableStateFlow(false)
        every { settings.addShellDevice(any(), any()) } answers {
            additions += firstArg<ShellDeviceConfig>()
            results += secondArg<(Boolean) -> Unit>()
            Job().apply { complete() }
        }
        every { settings.updateShellDevice(any(), any()) } answers {
            updates += firstArg<ShellDeviceConfig>()
            results += secondArg<(Boolean) -> Unit>()
            Job().apply { complete() }
        }
        compose.setContent {
            val context = LocalContext.current
            strings = listOf(
                R.string.shell_add_device, R.string.shell_device_name, R.string.shell_device_desc,
                R.string.shell_device_url, R.string.shell_device_key, R.string.save, R.string.back,
                R.string.custom_provider_name_error,
            ).associateWith { context.getString(it) }
            CompositionLocalProvider(
                LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true),
                LocalSettingsScrollRetention provides null,
            ) {
                MaterialTheme {
                    Box(Modifier.size(400.dp, 800.dp)) {
                        if (pageVisible) SettingsShellPage(viewModel) {
                            backCalls++
                            pageVisible = false
                        }
                    }
                }
            }
        }
    }

    private fun button(label: Int) = compose.onNode(hasText(strings.getValue(label)) and hasClickAction())
    private fun field(label: Int) = compose.onNode(hasText(strings.getValue(label)) and hasSetTextAction())
    private fun save() = button(R.string.save)
    private fun add() = button(R.string.shell_add_device)
    private fun nameError() = compose.onNodeWithText(strings.getValue(R.string.custom_provider_name_error))

    private fun addDraft() {
        add().performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        field(R.string.shell_device_name).assertExists()
    }

    private fun replace(label: Int, value: String) {
        field(label).performScrollTo().performTextReplacement(value)
    }

    private fun assertValue(label: Int, value: String) {
        field(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    }

    private fun assertNoPersistence() {
        verify(exactly = 0) { settings.addShellDevice(any(), any()) }
        verify(exactly = 0) { settings.updateShellDevice(any(), any()) }
    }

    @Test
    fun addCreatesOnlyBlankLocalDraftAndFieldChangesAndBackNeverCreateARecord() {
        showPage()
        addDraft()
        listOf(
            R.string.shell_device_name, R.string.shell_device_desc,
            R.string.shell_device_url, R.string.shell_device_key,
        ).forEach { assertValue(it, "") }
        save().assertIsNotEnabled()
        add().assertIsNotEnabled()
        assertNoPersistence()
        compose.runOnIdle { assertTrue(devices.value.isEmpty()) }

        replace(R.string.shell_device_url, " https://draft.example.test:14216 ")
        replace(R.string.shell_device_key, " token ")
        replace(R.string.shell_device_desc, " Draft description ")
        replace(R.string.shell_device_name, " Draft host ")
        save().assertIsEnabled()
        assertNoPersistence()
        compose.onNodeWithContentDescription(strings.getValue(R.string.back)).performClick()
        field(R.string.shell_device_name).assertDoesNotExist()
        assertNoPersistence()
        compose.runOnIdle {
            assertEquals(1, backCalls)
            assertTrue(devices.value.isEmpty())
        }
    }

    @Test
    fun blankDuplicateAndReservedNamesDisableSaveWhileConflictsShowInlineError() {
        val existing = ShellDeviceConfig(id = "existing", name = "  Build Host  ")
        showPage(listOf(existing))
        addDraft()
        replace(R.string.shell_device_name, "   ")
        save().assertIsNotEnabled()
        for (name in listOf("build host", "  BUILD HOST  ", "  lOcAl SaNdBoX  ")) {
            replace(R.string.shell_device_name, name)
            save().assertIsNotEnabled()
            nameError().assertExists()
            field(R.string.shell_device_name).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            assertValue(R.string.shell_device_name, name)
        }
        replace(R.string.shell_device_name, " Unique host ")
        save().assertIsEnabled()
        nameError().assertDoesNotExist()
        assertNoPersistence()
        compose.runOnIdle { assertEquals(listOf(existing), devices.value) }
    }

    @Test
    fun explicitSaveTrimsRecordRetainsRejectedDraftAndClearsItOnlyAfterSuccess() {
        showPage()
        addDraft()
        replace(R.string.shell_device_url, " https://build.example.test:14216 ")
        replace(R.string.shell_device_key, " test-token ")
        replace(R.string.shell_device_desc, " Build machine ")
        replace(R.string.shell_device_name, " Build host ")
        assertNoPersistence()
        save().performScrollTo().performClick()
        save().assertIsNotEnabled()
        add().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, additions.size)
            val submitted = additions.single()
            assertTrue(submitted.id.isNotBlank())
            assertEquals(
                ShellDeviceConfig(
                    id = submitted.id, name = "Build host", description = "Build machine",
                    serverUrl = "https://build.example.test:14216", apiKey = "test-token",
                ),
                submitted,
            )
            assertTrue(devices.value.isEmpty())
            results.single()(false)
        }
        assertValue(R.string.shell_device_name, " Build host ")
        assertValue(R.string.shell_device_desc, " Build machine ")
        assertValue(R.string.shell_device_url, " https://build.example.test:14216 ")
        nameError().assertExists()
        add().assertIsNotEnabled()
        save().assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, additions.size)
            assertEquals(additions.first(), additions.last())
            devices.value = listOf(additions.last())
            results.last()(true)
        }
        field(R.string.shell_device_name).assertDoesNotExist()
        save().assertDoesNotExist()
        nameError().assertDoesNotExist()
        compose.onNodeWithText("Build host").assertExists()
        add().assertIsEnabled()
        verify(exactly = 2) { settings.addShellDevice(any(), any()) }
        verify(exactly = 0) { settings.updateShellDevice(any(), any()) }
    }

    @Test
    fun existingDeviceAcceptsItsOwnTrimmedCaseVariantButBackNeverUpdatesIt() {
        val existing = ShellDeviceConfig(
            id = "existing", name = "Build Host", description = "Original",
            serverUrl = "https://existing.example.test:14216", apiKey = "original-token",
        )
        showPage(listOf(existing))
        compose.onNode(hasText(existing.name) and hasClickAction()).performScrollTo().performClick()
        field(R.string.shell_device_name).assertExists()
        save().assertIsEnabled()
        replace(R.string.shell_device_name, "  bUiLd HoSt  ")
        replace(R.string.shell_device_desc, " Unsaved change ")
        save().assertIsEnabled()
        nameError().assertDoesNotExist()
        assertValue(R.string.shell_device_name, "  bUiLd HoSt  ")
        assertNoPersistence()
        compose.onNodeWithContentDescription(strings.getValue(R.string.back)).performClick()
        field(R.string.shell_device_name).assertDoesNotExist()
        assertNoPersistence()
        compose.runOnIdle {
            assertEquals(1, backCalls)
            assertEquals(listOf(existing), devices.value)
        }
    }
}
