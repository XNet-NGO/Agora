package com.newoether.agora.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.app.Application
import androidx.compose.ui.platform.LocalView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelImportItemTest {
    @get:Rule val compose = createComposeRule()

    @Test fun leftProgressCrossfadesWithoutMovingTheTitle() = verifyProgress(reduced = false)

    @Test fun reducedMotionKeepsTheSameSlotAndAllowedFade() = verifyProgress(reduced = true)

    private fun verifyProgress(reduced: Boolean) {
        var importing by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduced)) {
                MaterialTheme {
                    ModelImportItem("Import model", "A GGUF model", Icons.Default.AutoAwesome,
                        importing, {}, Modifier.width(380.dp).testTag("import"))
                }
            }
        }
        val title = compose.onNodeWithText("Import model", useUnmergedTree = true)
        val beforeTitle = title.fetchSemanticsNode().boundsInRoot
        val leading = compose.onNodeWithTag("model-import-leading", useUnmergedTree = true)
        val beforeLeading = leading.fetchSemanticsNode().boundsInRoot
        assertTrue(beforeLeading.right < beforeTitle.left)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { importing = true }
        advance(64)
        compose.onNodeWithTag("model-import-progress", useUnmergedTree = true).assertExists()
        assertEquals(beforeLeading, leading.fetchSemanticsNode().boundsInRoot)
        assertEquals(beforeTitle, title.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("import").assertIsNotEnabled()
        advance(300)
        val progress = compose.onNodeWithTag("model-import-progress", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(beforeLeading, progress)
        compose.runOnIdle { importing = false }
        advance(64)
        compose.onNodeWithTag("model-import-progress", useUnmergedTree = true).assertExists()
        advance(300)
        compose.onNodeWithTag("model-import-progress", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(beforeTitle, title.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun selectedFilenameStaysPrimaryAndCrossfadesWithoutLosingTheRowAction() {
        var title by mutableStateOf("Import model")
        var clicks = 0
        var primary = androidx.compose.ui.graphics.Color.Unspecified
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            MaterialTheme {
                primary = MaterialTheme.colorScheme.primary
                ModelImportItem(title, "A GGUF model", Icons.Default.AutoAwesome, false,
                    { clicks++ }, Modifier.width(380.dp).testTag("import"))
            }
        }
        compose.onNodeWithTag("import").performClick()
        assertEquals(1, clicks)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { title = "selected.gguf" }
        advance(64)
        compose.onNodeWithText("Import model", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("selected.gguf", useUnmergedTree = true).assertExists()
        advance(300)
        compose.onNodeWithText("Import model", useUnmergedTree = true).assertDoesNotExist()
        val bounds = compose.onNodeWithText("selected.gguf", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        var primaryPixels = 0
        compose.runOnIdle {
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                for (x in bounds.left.toInt() until bounds.right.toInt()) {
                    val pixel = androidx.compose.ui.graphics.Color(image.getPixel(x, y))
                    if (kotlin.math.abs(pixel.red - primary.red) < .02f &&
                        kotlin.math.abs(pixel.green - primary.green) < .02f &&
                        kotlin.math.abs(pixel.blue - primary.blue) < .02f) primaryPixels++
                }
            }
            image.recycle()
        }
        assertTrue("The selected filename uses primary, not onSurface", primaryPixels > 5)
        compose.onNodeWithTag("import").performClick()
        assertEquals(2, clicks)
    }

    private fun advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }
}
