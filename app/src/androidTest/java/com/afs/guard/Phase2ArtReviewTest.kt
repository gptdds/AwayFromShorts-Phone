package com.afs.guard

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import com.afs.feature.phase2.FocusView
import com.afs.feature.phase2.Phase2ArtReviewStage
import com.afs.feature.phase2.Phase2FocusMode
import com.afs.feature.phase2.Phase2IndicatorShape
import com.afs.feature.phase2.Phase2PrototypeKind
import com.afs.feature.phase2.Phase2Snapshot
import com.afs.feature.phase2.Phase2VisualPlugin
import com.afs.guard.style.EffectQuality
import com.afs.guard.style.PaperStyle
import com.afs.guard.style.RendererFactory
import com.afs.guard.style.StyleRenderer
import com.afs.guard.style.StyleRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Phase2ArtReviewTest {
    @get:Rule val compose = createComposeRule()
    private val temporaryStyle = "qa_phase2_plugin"

    @After
    fun removeTemporaryStyle() {
        RegistryFixtures.remove(temporaryStyle)
    }

    private val snapshot = Phase2Snapshot(
        focus = FocusView(
            remainingMs = 2_838_000L,
            progress = .40f,
            nextChangeAt = 4_000_000L,
            lastChangeAt = 1_000_000L,
            blockedApps = 25,
            managedApps = 29,
            status = "专注中",
            detail = "controlled prototype state"
        ),
        apps = emptyList(),
        capturedAt = 2_000_000L
    )

    private fun show(
        plugin: Phase2VisualPlugin,
        prototype: Phase2PrototypeKind,
        clean: Boolean = false,
        stressDaily: Boolean = false
    ) {
        compose.setContent {
            Phase2ArtReviewStage(
                snapshot = snapshot,
                plugin = plugin,
                quality = EffectQuality.LOW,
                active = true,
                initialPrototype = prototype,
                stressDaily = stressDaily,
                clean = clean
            )
        }
    }

    @Test
    fun focusControlsAreDistinctAndStateful() {
        show(ComposePhase2Catalog.resolve("liquidglass"), Phase2PrototypeKind.FOCUS)
        compose.onNodeWithTag("phase2-focus-ring").assertExists()
        compose.onNodeWithTag("phase2-focus-start").performClick()
        compose.onNodeWithTag("phase2-focus-pause").performClick()
        compose.onNodeWithTag("phase2-focus-end").performClick()
        compose.onNodeWithTag("phase2-focus-ring").assertExists()
    }

    @Test
    fun quotaDragPreviewsAndLongPressLocks() {
        show(ComposePhase2Catalog.resolve("liquidglass"), Phase2PrototypeKind.QUOTA)
        val quota = compose.onNodeWithTag("phase2-quota-prototype")
        val initial = quota.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
            .joinToString()
        assertTrue(initial.contains("当前 60 分钟"))

        quota.performTouchInput { swipeRight() }
        val dragged = quota.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
            .joinToString()
        assertFalse(dragged.contains("当前 60 分钟"))

        quota.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(620)
        val locked = quota.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
            .joinToString()
        quota.performTouchInput { up() }
        assertTrue(locked, locked.contains("边界已锁定"))
    }

    @Test
    fun allDirectionsMountTheirOwnDailyAndQuotaObjects() {
        val plugins = ComposePhase2Catalog.all()
        var plugin by mutableStateOf(plugins.first())
        compose.setContent {
            Phase2ArtReviewStage(
                snapshot = snapshot,
                plugin = plugin,
                quality = EffectQuality.LOW,
                active = true,
                initialPrototype = Phase2PrototypeKind.DAILY
            )
        }
        plugins.forEach { next ->
            compose.runOnIdle { plugin = next }
            compose.waitForIdle()
            compose.onNodeWithTag("phase2-daily-prototype").assertExists()
            compose.onNodeWithTag("phase2-daily-row-0").assertExists()
            compose.onNodeWithTag("phase2-daily-primary").assertExists()
        }
    }

    @Test
    fun cleanModeHidesEveryReviewControl() {
        show(ComposePhase2Catalog.resolve("fluidgradient"), Phase2PrototypeKind.QUOTA, clean = true)
        compose.onNodeWithTag("phase2-prototype-focus").assertDoesNotExist()
        compose.onNodeWithTag("phase2-cycle-direction").assertDoesNotExist()
        compose.onNodeWithTag("phase2-quota-prototype").assertExists()
    }

    @Test
    fun dailyStressDataReachesTheFiftiethBoundary() {
        show(ComposePhase2Catalog.resolve("liquidglass"), Phase2PrototypeKind.DAILY, stressDaily = true)
        compose.onNodeWithTag("phase2-daily-row-0").assertExists()
        compose.onNodeWithTag("phase2-daily-row-49").assertExists()
    }

    @Test
    fun catalogDiscoversANewStyleWithoutChangingTheReviewStage() {
        StyleRegistry.register(object : RendererFactory {
            override fun id() = temporaryStyle
            override fun displayName(context: Context) = temporaryStyle
            override fun create(): StyleRenderer = PaperStyle()
        })
        StyleRegistry.registerCapability(
            temporaryStyle,
            Phase2VisualPlugin::class.java
        ) {
            object : Phase2VisualPlugin {
                override val id = temporaryStyle
                override val displayName = "测试风格"
                override val order = 99
                override val indicatorColor = Color.White
                override val indicatorShape = Phase2IndicatorShape.SQUARE

                @androidx.compose.runtime.Composable
                override fun Backdrop(
                    active: Boolean,
                    quality: EffectQuality,
                    energy: Float,
                    anchor: Offset,
                    modifier: Modifier
                ) = Box(modifier)

                @androidx.compose.runtime.Composable
                override fun Focus(
                    focus: FocusView,
                    mode: Phase2FocusMode,
                    quality: EffectQuality,
                    active: Boolean,
                    onMode: (Phase2FocusMode) -> Unit,
                    onGesture: (Float, Offset) -> Unit,
                    modifier: Modifier
                ) = Box(modifier)

                @androidx.compose.runtime.Composable
                override fun Quota(
                    quality: EffectQuality,
                    active: Boolean,
                    onGestureEnergy: (Float, Offset) -> Unit,
                    modifier: Modifier
                ) = Box(modifier)

                @androidx.compose.runtime.Composable
                override fun Daily(
                    quality: EffectQuality,
                    active: Boolean,
                    stress: Boolean,
                    onGestureEnergy: (Float, Offset) -> Unit,
                    modifier: Modifier
                ) = Box(modifier)
            }
        }

        val discovered = ComposePhase2Catalog.all()
        assertEquals(temporaryStyle, discovered.last().id)
        assertEquals(temporaryStyle, ComposePhase2Catalog.resolve(temporaryStyle).id)
    }
}
