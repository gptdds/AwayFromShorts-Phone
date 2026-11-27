package com.afs.guard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.afs.guard.style.EffectQuality
import com.afs.style.api.*
import com.afs.style.spatial3d.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry

class SpatialInteractionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun springGesturePreservesButtonsScrollingAndStopsOnPause() {
        val renderer = SpatialComponents()
        val ui = Ui(InstrumentationRegistry.getInstrumentation().targetContext)
        var clicks = 0
        compose.runOnUiThread { renderer.resume() }
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalComponents provides renderer,
                LocalVisualEnvironment provides VisualEnvironment(ui.tokens(), ui.motionTokens(), EffectQuality.HIGH, true)) {
                LazyColumn(Modifier.fillMaxSize().testTag("spatial-list"), contentPadding = PaddingValues(16.dp)) {
                    items((0..29).toList()) { index -> renderer.Card(Modifier.fillMaxWidth().testTag("card-$index")) {
                        Text("应用 $index", Modifier.padding(16.dp))
                        renderer.Action({ clicks++ }, Modifier.testTag("action-$index"), true) { Text("查看") }
                    } }
                }
            } }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("card-0").performTouchInput { down(Offset(width * .85f, height * .2f)) }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnUiThread { assertTrue("touch did not start a spring", renderer.resources.activeCount > 0) }
        compose.onNodeWithTag("card-0").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("action-0").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithTag("spatial-list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("card-0").assertDoesNotExist()
        compose.runOnUiThread { renderer.pause(); assertEquals(0, renderer.resources.activeCount); renderer.release() }
    }
}
