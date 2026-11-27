package com.afs.guard

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase2NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<SharedHomeActivity>()

    @Before fun ready() {
        compose.waitUntil(15_000) {
            !compose.activity.homeModel.state.value.loading &&
                compose.activity.homeModel.state.value.snapshot != null
        }
    }

    @Test fun diagnosticsSettingsAndBackupAreRealComposeRoutes() {
        compose.onNodeWithTag("dashboard-open-apps").performScrollTo().performClick()
        compose.onNodeWithTag("app-rules-root").assertExists()
        compose.onNodeWithTag("app-rules-add").assertExists()
        compose.onNodeWithTag("app-rules-back").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("dashboard-root").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("dashboard-diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-root").assertExists()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("diagnostic-fluid-orb").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("diagnostic-fluid-orb").assertExists()
        compose.onNodeWithTag("diagnostics-back").performClick()

        compose.onNodeWithTag("dashboard-settings").performScrollTo().performClick()
        compose.onNodeWithTag("settings-root").assertExists()
        compose.onNodeWithTag("settings-backup").performScrollTo().performClick()
        compose.onNodeWithTag("backup-root").assertExists()
        compose.onNodeWithTag("backup-export").assertExists()
        compose.onNodeWithTag("backup-import").assertExists()
        compose.onNodeWithTag("backup-back").performClick()

        compose.onNodeWithTag("settings-diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-root").assertExists()
        compose.onNodeWithTag("diagnostics-back").performClick()
        compose.onNodeWithTag("settings-root").assertExists()
        compose.onNodeWithTag("settings-back").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("dashboard-root").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("dashboard-root").assertExists()
    }
}
