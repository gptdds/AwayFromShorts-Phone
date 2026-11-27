package com.afs.guard

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.EffectQualityManager
import com.afs.guard.style.StyleManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class Phase2VisualCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<SharedHomeActivity>()
    private val directory by lazy {
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "phase2-captures"
        ).apply { mkdirs() }
    }
    private lateinit var oldStyle: String
    private lateinit var oldQuality: String

    @Before fun ready() {
        oldStyle = StyleManager.selected(compose.activity)
        oldQuality = EffectQualityManager.preference(compose.activity)
        compose.waitUntil(15_000) {
            !compose.activity.homeModel.state.value.loading &&
                compose.activity.homeModel.state.value.snapshot != null
        }
        compose.runOnIdle {
            StyleManager.select(compose.activity, "liquidglass")
            EffectQualityManager.setPreference(compose.activity, "low")
        }
        compose.waitUntil(8_000) { compose.activity.componentRenderer?.id == "liquidglass" }
        compose.onNodeWithTag("dashboard-root").assertExists()
        compose.waitForIdle()
        capture(File(directory, "dashboard.png"))
    }

    @After fun restoreStyle() {
        compose.runOnIdle {
            StyleManager.select(compose.activity, oldStyle)
            EffectQualityManager.setPreference(compose.activity, oldQuality)
        }
    }

    @Test fun captureDiagnosticsSettingsAndBackup() {
        compose.onNodeWithTag("dashboard-diagnostics").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("diagnostics-root").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("diagnostic-fluid-orb").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        capture(File(directory, "diagnostics.png"))

        compose.onNodeWithTag("diagnostics-back").performClick()
        compose.onNodeWithTag("dashboard-settings").performScrollTo().performClick()
        compose.onNodeWithTag("settings-root").assertExists()
        capture(File(directory, "settings.png"))

        compose.onNodeWithTag("settings-backup").performScrollTo().performClick()
        compose.onNodeWithTag("backup-root").assertExists()
        capture(File(directory, "backup.png"))
        compose.onNodeWithTag("backup-back").performClick()
        compose.onNodeWithTag("settings-root").assertExists()

        compose.runOnIdle {
            StyleManager.select(compose.activity, "liquidglass")
            EffectQualityManager.setPreference(compose.activity, "low")
        }
        compose.waitUntil(8_000) { compose.activity.componentRenderer?.id == "liquidglass" }
        capture(File(directory, "settings-universe.png"))
        compose.onNodeWithTag("settings-diagnostics").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("diagnostics-root").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("diagnostic-fluid-orb").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        capture(File(directory, "diagnostics-universe.png"))
        compose.onNodeWithTag("diagnostics-back").performClick()
        compose.onNodeWithTag("settings-back").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("dashboard-root").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("dashboard-open-apps").performScrollTo().performClick()
        compose.onNodeWithTag("app-rules-root").assertExists()
        compose.onNodeWithTag("app-rules-add").assertExists()
        compose.waitForIdle()
        capture(File(directory, "app-rules-universe.png"))
        val target = compose.activity.homeModel.state.value.snapshot
            ?.apps
            ?.firstOrNull()
            ?.packageName
        if (target != null) {
            compose.onNodeWithTag("phase2-app-card-$target").performScrollTo().performClick()
            compose.onNodeWithTag("timeline-route").assertExists()
            compose.waitForIdle()
            capture(File(directory, "timeline-universe.png"))
            compose.onNodeWithTag("timeline-back").performClick()
            compose.onNodeWithTag("app-rules-root").assertExists()
        }

        assertTrue(directory.listFiles().orEmpty().all { it.length() > 0 })
    }

    private fun capture(file: File) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        requireNotNull(bitmap) { "screenshot unavailable" }
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
    }
}
