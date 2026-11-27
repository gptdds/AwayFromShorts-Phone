package com.afs.guard

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.afs.feature.phase2.AppView
import com.afs.feature.phase2.FocusView
import com.afs.feature.phase2.MinuteWindow
import com.afs.feature.phase2.Phase2Screen
import com.afs.feature.phase2.Phase2Snapshot
import com.afs.guard.style.EffectQuality
import org.junit.Rule
import org.junit.Test

class Phase2ComponentTest {
    @get:Rule val compose = createComposeRule()

    private val snapshot = Phase2Snapshot(
        focus = FocusView(
            remainingMs = 3_600_000L,
            progress = .42f,
            nextChangeAt = 4_000_000L,
            lastChangeAt = 1_000_000L,
            blockedApps = 2,
            managedApps = 3,
            status = "守卫中",
            detail = "testing"
        ),
        apps = listOf(
            AppView(
                packageName = "com.example.one",
                label = "示例一",
                blocked = true,
                status = "时段冻结",
                allowedText = "21:00-23:00",
                nextChangeAt = 4_000_000L,
                daysMask = 0x7F,
                freezeMode = false,
                windows = listOf(MinuteWindow(21 * 60, 23 * 60)),
                gate = true,
                installed = true
            ),
            AppView(
                packageName = "com.example.two",
                label = "示例二",
                blocked = false,
                status = "可使用",
                allowedText = "全天",
                nextChangeAt = 4_000_000L,
                daysMask = 0x7F,
                freezeMode = true,
                windows = emptyList(),
                gate = false,
                installed = true
            )
        ),
        capturedAt = 2_000_000L
    )

    private fun show() {
        compose.setContent {
            Phase2Screen(
                snapshot = snapshot,
                loading = false,
                error = null,
                quality = EffectQuality.LOW,
                active = false,
                onReload = {}
            )
        }
    }

    @Test
    fun focusRingLongPressShowsRealDetail() {
        show()
        compose.onNodeWithTag("phase2-focus-ring").performTouchInput { longClick() }
        compose.onNodeWithText("状态详情").assertExists()
        compose.onNodeWithText("最近计划变化").assertExists()
        compose.onNodeWithText("下一次计划变化").assertExists()
    }

    @Test
    fun tabsExposeCardsAndEditableTimeline() {
        show()
        compose.onNodeWithTag("phase2-tab-1").performClick()
        compose.onAllNodes(hasTestTag("phase2-app-card-com.example.one")).assertCountEquals(1)
        compose.onNodeWithTag("phase2-app-card-com.example.one").performClick()
        compose.onNodeWithText("模式").assertExists()

        compose.onNodeWithTag("phase2-tab-2").performClick()
        compose.onNodeWithTag("phase2-timeline").assertExists()
        compose.onNodeWithTag("phase2-add-window").performClick()
        compose.onNodeWithText("时段 2").assertExists()
        compose.onNodeWithTag("phase2-undo").performClick()
        compose.onNodeWithText("时段 2").assertDoesNotExist()
    }
}
