package com.newoether.agora.ui.remote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.newoether.agora.remote.RemoteDeviceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w400dp-h250dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteAgentChipRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun statusChangesRetainOneInformationalChipAndItsGeometry() {
        var status by mutableStateOf(RemoteDeviceStatus.CONNECTED)
        compose.setContent { MaterialTheme { RemoteAgentChipRow(status) } }
        compose.onNodeWithContentDescription("Codex Desktop, Online").assertExists().assertHasNoClickAction()
        val initial = compose.onNodeWithText("Codex Desktop").getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { status = RemoteDeviceStatus.ERROR }
        compose.mainClock.advanceTimeByFrame()
        repeat(4) {
            compose.mainClock.advanceTimeBy(50)
            assertEquals(initial, compose.onNodeWithText("Codex Desktop").getUnclippedBoundsInRoot())
        }
        compose.onNodeWithContentDescription("Codex Desktop, Offline").assertExists()
        compose.onNodeWithText("Claude Code").assertDoesNotExist()
        compose.runOnIdle { status = RemoteDeviceStatus.CONNECTING }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithContentDescription("Codex Desktop, Connecting").assertExists()
        assertEquals(initial, compose.onNodeWithText("Codex Desktop").getUnclippedBoundsInRoot())
    }

    @Test fun capsuleGeometryAndPointerEventsReachPlatformRippleWithoutAddingAnAction() {
        lateinit var drawingView: View
        var containerColor = Color.Unspecified
        compose.setContent {
            drawingView = LocalView.current
            MaterialTheme {
                containerColor = MaterialTheme.colorScheme.secondaryContainer
                Box(Modifier.size(300.dp, 80.dp).background(Color.White)) {
                    RemoteAgentChipRow(RemoteDeviceStatus.CONNECTED)
                }
            }
        }
        val chip = compose.onNodeWithTag("remote-agent-chip", useUnmergedTree = true)
        val bounds = chip.fetchSemanticsNode().boundsInRoot
        assertEquals(40f, bounds.height, 0.01f)
        val surface = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        try {
            compose.runOnUiThread {
                val canvas = Canvas(surface)
                canvas.translate(-bounds.left, -bounds.top)
                drawingView.draw(canvas)
            }
            assertEquals("The visible surface has capsule corners", android.graphics.Color.WHITE, surface.getPixel(2, 2))
            assertEquals("The old outline position uses the selected container fill",
                containerColor.toArgb(), surface.getPixel(surface.width / 2, 0))
            assertTrue("The capsule contains a painted surface", surface.getPixel(surface.width / 2, surface.height / 2) != android.graphics.Color.WHITE)
        } finally {
            surface.recycle()
        }
        fun ripples(view: View): List<android.graphics.drawable.RippleDrawable> = buildList {
            (view.background as? android.graphics.drawable.RippleDrawable)?.let { add(it) }
            if (view is android.view.ViewGroup) {
                repeat(view.childCount) { addAll(ripples(view.getChildAt(it))) }
            }
        }
        // API 35 patterned ripples skip software Canvas drawing. Check the real platform
        // drawable's state instead; visible wave pixels require hardware rendering.
        chip.performTouchInput { down(center) }
        lateinit var ripple: android.graphics.drawable.RippleDrawable
        try {
            compose.runOnIdle {
                val pressed = ripples(drawingView.rootView).filter { android.R.attr.state_pressed in it.state }
                assertEquals("A real pointer press reaches one platform ripple", 1, pressed.size)
                ripple = pressed.single()
            }
            compose.onNodeWithContentDescription("Codex Desktop, Online").assertHasNoClickAction()
        } finally {
            chip.performTouchInput { up() }
        }
        compose.runOnIdle { assertTrue("Release clears the platform press", android.R.attr.state_pressed !in ripple.state) }
        chip.performTouchInput { down(center) }
        try {
            compose.runOnIdle { assertTrue("A new press reaches the same ripple", android.R.attr.state_pressed in ripple.state) }
        } finally {
            chip.performTouchInput { cancel() }
        }
        compose.runOnIdle { assertTrue("Cancellation clears the platform press", android.R.attr.state_pressed !in ripple.state) }
    }
}
