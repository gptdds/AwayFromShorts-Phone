package com.afs.guard

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.feature.phase2.GlassButton
import com.afs.feature.phase2.GlassCard
import com.afs.feature.phase2.GlassDialog
import com.afs.feature.phase2.GlassNavigationBar
import com.afs.feature.phase2.GlassNavigationItem
import com.afs.guard.style.EffectQuality
import com.afs.style.api.LocalVisualEnvironment
import com.afs.style.api.VisualEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassComponentsTest {
    @get:Rule val compose = createComposeRule()

    private val environment = VisualEnvironment(
        Ui(InstrumentationRegistry.getInstrumentation().targetContext).tokens(),
        Ui(InstrumentationRegistry.getInstrumentation().targetContext).motionTokens(),
        EffectQuality.LOW,
        true
    )

    @Test fun glassButtonCardNavigationAndDialogRemainInteractive() {
        var dialog by mutableStateOf(false)
        var selected by mutableStateOf("home")
        compose.setContent {
            CompositionLocalProvider(LocalVisualEnvironment provides environment) {
                Column {
                    GlassCard(Modifier.testTag("glass-card")) {
                        Text("卡片内容")
                    }
                    GlassButton(
                        onClick = { dialog = true },
                        tag = "glass-button"
                    ) { Text("打开") }
                    GlassNavigationBar(
                        items = listOf(
                            GlassNavigationItem("home", "主页", "主页标签"),
                            GlassNavigationItem("guard", "守卫", "守卫标签")
                        ),
                        selectedId = selected,
                        onSelect = { selected = it.id },
                        modifier = Modifier.testTag("glass-nav")
                    )
                    if (dialog) {
                        GlassDialog(
                            onDismiss = { dialog = false },
                            title = "玻璃弹窗",
                            confirmLabel = "确认",
                            onConfirm = { dialog = false }
                        ) {
                            Text("弹窗内容")
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("glass-card").assertExists()
        compose.onNodeWithTag("glass-nav-home").assertIsSelected()
        compose.onNodeWithTag("glass-nav-guard").performClick()
        compose.onNodeWithTag("glass-nav-guard").assertIsSelected()
        compose.onNodeWithTag("glass-button").performClick()
        compose.onNodeWithText("玻璃弹窗").assertExists()
        compose.onNodeWithText("确认").performClick()
        compose.onNodeWithText("玻璃弹窗").assertDoesNotExist()
    }
}
