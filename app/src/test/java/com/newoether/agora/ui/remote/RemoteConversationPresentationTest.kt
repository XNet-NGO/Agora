package com.newoether.agora.ui.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.remote.*
import com.newoether.agora.viewmodel.AnimatedScrollRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h800dp-mdpi")
class RemoteConversationPresentationTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(RemoteState(
        restoring = false, deviceId = "device",
        devices = listOf(RemoteDevice("device", "Computer", "http://computer", RemoteDeviceStatus.CONNECTED,
            desktopStatus = RemoteDeviceStatus.CONNECTED)),
        session = RemoteSession("draft", "", "", 0), draftSessionId = "draft",
        drafts = mapOf("device/draft" to "Unsent draft"),
    ))
    private val vmState = MutableStateFlow(state)
    private val vm = mockk<RemoteViewModel>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show() {
        every { vm.state } answers { vmState.apply { value = state } }
        every { vm.animatedScrollRequest } returns MutableStateFlow<AnimatedScrollRequest?>(null)
        every { settings.blurEffectsEnabled } returns MutableStateFlow(false)
        every { settings.amoledEnabled } returns MutableStateFlow(false)
        every { settings.parseInlineDollarMath } returns MutableStateFlow(false)
        every { settings.stickToBottom } returns MutableStateFlow(true)
        every { settings.autoWrapCodeBlocks } returns MutableStateFlow(true)
        every { settings.toolCallDisplayMode } returns MutableStateFlow(ToolCallDisplayModes.DEFAULT)
        every { settings.thinkingSegmentDisplayMode } returns MutableStateFlow(ThinkingSegmentDisplayModes.DEFAULT)
        every { settings.autoExpandActiveGroup } returns MutableStateFlow(true)
        compose.setContent {
            MaterialTheme {
                RemoteConversation(state, vm, settings, true, {}, {}, { _, _ -> })
            }
        }
    }

    @Test fun detailsRemainsVisibleDuringLoadingWithBothUnavailableRowsDisabled() {
        state = state.copy(models = emptyList(), modelsLoading = true, loading = true)
        show()
        compose.onNodeWithContentDescription(text(R.string.tools)).assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.thinking)).assertExists().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.openai_service_tier_title)).assertExists().assertIsNotEnabled()
        verify(exactly = 0) { vm.setThinkingLevel(any()) }
        verify(exactly = 0) { vm.setServiceTier(any()) }
    }
    @Test fun readOnlySourceRetainsTheDetailsButtonAndDisabledSettingsRows() {
        val source = RemoteAdapter("cli", "codex", "cli", "connected",
            capabilities = RemoteCapabilities(history = true))
        state = state.copy(devices = state.devices.map { it.copy(multiAdapter = true, adapters = listOf(source)) },
            adapterSelections = mapOf("device" to "cli"),
            models = listOf(RemoteModel("model", "Model", true, listOf("low", "high"), "low",
                listOf(RemoteServiceTier("fast", "Fast")))))
        show()
        compose.onNodeWithContentDescription(text(R.string.tools)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.thinking)).assertExists().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.openai_service_tier_title)).assertExists().assertIsNotEnabled()
    }
    @Test fun supportedThinkingAndUnsupportedTierAreDisabledIndependently() {
        state = state.copy(models = listOf(RemoteModel("model", "Model", true, listOf("low", "high"), "low")))
        show()
        compose.onNodeWithContentDescription(text(R.string.tools)).performClick()
        compose.onNodeWithText(text(R.string.thinking)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.openai_service_tier_title)).assertIsNotEnabled()
    }
    @Test fun unknownDeliveryUsesTheNextSendDirectlyWithoutAnAcknowledgmentAction() {
        val owner = requireNotNull(state.owner)
        state = state.copy(attempts = mapOf(owner to RemoteAttempt("previous", "Unsent draft", RemoteDelivery.UNKNOWN)))
        show()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft")
            .performTextReplacement("Edited retry")
        compose.onNodeWithContentDescription(text(R.string.action)).assertIsEnabled().performClick()
        compose.waitForIdle()
        verify(exactly = 1) { vm.send() }
        verify { vm.editDraft(owner, "Edited retry") }
        compose.onNode(hasSetTextAction()).assertTextContains("Edited retry")
        compose.onNodeWithText(text(R.string.remote_unknown)).assertDoesNotExist()
        compose.onNodeWithText("Allow Sending").assertDoesNotExist()
        verify(exactly = 0) { vm.retryConnection() }
        verify(exactly = 0) { vm.newSession() }
    }

    @Test fun draftStaysEditableThroughFailureAndSafeRetryWithoutASecondSubmissionPath() {
        show()
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex Desktop").assertIsDisplayed()
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals("Computer")
        compose.onNodeWithTag("remote-welcome-mark").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft").performTextReplacement("Unsent draft continued")
        compose.runOnIdle {
            state = state.copy(failure = RemoteFailure.NETWORK,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.ERROR) })
        }
        compose.onNodeWithText(text(R.string.remote_offline), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.remote_empty_retry_hint)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft continued")
        compose.onNodeWithText(text(R.string.retry)).performClick()
        verify(exactly = 1) { vm.retryConnection() }
        verify(exactly = 0) { vm.send() }
        verify(exactly = 0) { vm.newSession() }
        compose.runOnIdle {
            state = state.copy(failure = null,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTING) })
        }
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex Desktop").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.remote_connecting), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft continued").performTextReplacement("Unsent draft continued more")
        compose.waitForIdle()
        verify { vm.editDraft("device/draft", "Unsent draft continued more") }
    }

    @Test fun existingOpeningShowsOnlyTheCanonicalLoadingIndicatorWithoutConnectionCopy() {
        state = state.copy(draftSessionId = null, loading = true)
        show()
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertDoesNotExist()
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onNodeWithTag("remote-welcome-mark").assertDoesNotExist()
        compose.onNodeWithTag("remote-welcome-device").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )).assertCountEquals(1)
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft")
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false, failure = RemoteFailure.SERVICE) }
        compose.onNodeWithText(text(R.string.remote_empty_retry_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_online), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.retry)).assertIsEnabled().performClick()
        verify(exactly = 1) { vm.retryConnection() }
        verify(exactly = 0) { vm.send() }
    }

    @Test fun pendingDraftKeepsActualTopBarStatusAndLoadingHistoryImmediatelySuppressesAnEnteringEmptyBody() {
        state = state.copy(loading = true,
            devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTING) })
        show()
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals("Codex Desktop")
        compose.onNodeWithTag("remote-welcome-device").assertTextEquals("Computer")
        compose.onNodeWithText(text(R.string.remote_connecting), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.remote_empty_connecting_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            state = state.copy(session = RemoteSession("history", "", "", 0), draftSessionId = null)
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )).assertCountEquals(1)
        compose.runOnIdle { state = state.copy(loading = false, failure = RemoteFailure.SERVICE); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithTag("remote-welcome-headline").assertTextEquals(text(R.string.remote_empty_retry_hint))
        compose.onNodeWithText(text(R.string.retry)).assertIsEnabled()
        val message = ChatMessage(id = "arriving", text = "Arrived during entry", participant = Participant.USER)
        every { vm.observeMessage(any(), "arriving") } returns flowOf(message)
        every { vm.cachedMessage(any(), "arriving") } returns message
        val groups = listOf(RemoteMessageGroup(message, emptyList()))
        compose.runOnIdle {
            state = state.copy(hydrationEnabled = true, runtime = RemoteRuntime("idle"), messageGroups = groups)
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(320)
        compose.waitForIdle()
        compose.onNodeWithText("Arrived during entry", substring = true).assertExists()
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onNodeWithTag("remote-welcome-mark").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.retry)).assertDoesNotExist()
        assertEquals(groups, state.messageGroups)
        verify(exactly = 0) { vm.retryConnection() }
        verify(exactly = 0) { vm.send() }
    }

    @Test fun retryKeepsFailureAndActionGeometryAcrossAutomaticRecoveryAndUsesOneCover() {
        state = state.copy(draftSessionId = null, failure = RemoteFailure.NETWORK,
            devices = state.devices.map { it.copy(status = RemoteDeviceStatus.ERROR) })
        show()
        val retry = compose.onNodeWithText(text(R.string.retry))
        val bounds = retry.getUnclippedBoundsInRoot()
        repeat(3) {
            compose.runOnIdle { state = state.copy(devices = state.devices.map { it.copy(status = RemoteDeviceStatus.CONNECTING) }) }
            compose.onNodeWithTag("remote-welcome-headline").assertTextEquals(text(R.string.remote_empty_retry_hint))
            retry.assertIsDisplayed().assertIsEnabled()
            assertEquals(bounds, retry.getUnclippedBoundsInRoot())
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
            compose.runOnIdle { state = state.copy(devices = state.devices.map { it.copy(status = RemoteDeviceStatus.ERROR) }) }
        }
        retry.performTouchInput { click() }
        verify(exactly = 1) { vm.retryConnection() }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(loading = true); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(240)
        compose.waitForIdle()
        val ring = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
        ring.assertWidthIsEqualTo(androidx.compose.ui.unit.Dp(48f)).assertHeightIsEqualTo(androidx.compose.ui.unit.Dp(48f))
        retry.assertIsNotEnabled()
        assertEquals(bounds, retry.getUnclippedBoundsInRoot())
        retry.performTouchInput { click() }
        verify(exactly = 1) { vm.retryConnection() }
        compose.runOnIdle { state = state.copy(loading = false); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        ring.assertExists()
        retry.performTouchInput { click() }
        verify(exactly = 1) { vm.retryConnection() }
        compose.mainClock.advanceTimeBy(240)
        compose.waitForIdle()
        ring.assertDoesNotExist()
        retry.assertIsEnabled().performTouchInput { click() }
        verify(exactly = 2) { vm.retryConnection() }
        verify(exactly = 0) { vm.send() }
        verify(exactly = 0) { vm.newSession() }
    }

    @Test fun foregroundDraftRetryUsesCoverAndLoadedHistoryRecoveryDoesNot() {
        state = state.copy(loading = true, failure = RemoteFailure.SERVICE)
        show()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft").performTextReplacement("Kept during retry")
        val message = ChatMessage(id = "loaded", text = "Retained during retry", participant = Participant.USER)
        every { vm.observeMessage(any(), "loaded") } returns flowOf(message)
        every { vm.cachedMessage(any(), "loaded") } returns message
        compose.runOnIdle {
            state = state.copy(draftSessionId = null, hydrationEnabled = true,
                messageGroups = listOf(RemoteMessageGroup(message, emptyList())))
        }
        compose.onNodeWithText("Retained during retry", substring = true).assertExists()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertTextContains("Kept during retry")
        verify(exactly = 0) { vm.retryConnection() }
        verify(exactly = 0) { vm.send() }
    }

    @Test fun admittedHistoryRemainsRenderedAfterConnectionFailureWithoutAnEmptyView() {
        val message = ChatMessage(id = "answer", text = "Retained answer", participant = Participant.USER)
        every { vm.observeMessage(any(), "answer") } returns flowOf(message)
        every { vm.cachedMessage(any(), "answer") } returns message
        state = state.copy(draftSessionId = null, hydrationEnabled = true,
            runtime = RemoteRuntime("idle"), messageGroups = listOf(RemoteMessageGroup(message, emptyList())))
        show()
        compose.onNodeWithText("Retained answer", substring = true).assertExists()
        compose.runOnIdle {
            state = state.copy(hydrationEnabled = false, runtime = null, failure = RemoteFailure.NETWORK,
                devices = state.devices.map { it.copy(status = RemoteDeviceStatus.ERROR) })
        }
        compose.onNodeWithText("Retained answer", substring = true).assertExists()
        compose.onNodeWithTag("remote-empty-conversation").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertTextContains("Unsent draft")
        verify(exactly = 0) { vm.retryConnection() }
    }
}
