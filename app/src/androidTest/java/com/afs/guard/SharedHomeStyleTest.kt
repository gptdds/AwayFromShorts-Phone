package com.afs.guard

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.afs.guard.style.EffectQualityManager
import com.afs.guard.style.StyleManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class SharedHomeStyleTest {
    // Configure before Activity launch: an inherited HIGH animation never reaches Compose idle.
    @get:Rule(order = 0) val preferences = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val context = instrumentation.targetContext
                val bridge = StylePreferencesBridge.get(context)
                runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
                val oldStyle = StyleManager.selected(context)
                val oldQuality = EffectQualityManager.preference(context)
                try {
                    instrumentation.runOnMainSync {
                        EffectQualityManager.setPreference(context, "low")
                        StyleManager.select(context, "paper")
                    }
                    runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
                    base.evaluate()
                } finally {
                    instrumentation.runOnMainSync {
                        StyleManager.select(context, oldStyle)
                        EffectQualityManager.setPreference(context, oldQuality)
                        RegistryFixtures.remove("qa_home_failed_style")
                    }
                    runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
                }
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<SharedHomeActivity>()
    private lateinit var rules: String
    @Before fun setup() {
        compose.runOnIdle {
            rules = RuleStore.exportRules(compose.activity)
        }
        compose.waitUntil(15_000) {
            !compose.activity.homeModel.state.value.loading &&
                compose.activity.homeModel.state.value.snapshot != null
        }
    }
    @After fun restore() {
        compose.runOnIdle {
            if (::rules.isInitialized) assertEquals(rules, RuleStore.exportRules(compose.activity))
        }
    }
    @Test fun switchKeepsDashboardDraftFilterNavigationAndViewModel() {
        val model = compose.activity.homeModel
        val appearanceModel = compose.activity.appearanceModel
        val repository = compose.activity.phase2Repository
        val reads = repository.readCount
        compose.onNodeWithTag("dashboard-root").assertExists()
        compose.onNodeWithTag("phase2-focus-ring").assertExists()
        compose.onNodeWithTag("dashboard-open-apps").performClick()
        compose.onNodeWithTag("app-rules-root").assertExists()
        compose.onNodeWithTag("app-rules-search").performTextInput("保留搜索草稿")
        compose.onNodeWithTag("app-rules-blocked").performClick()
        compose.onNodeWithTag("app-rules-back").performClick()
        compose.onNodeWithTag("dashboard-appearance").performClick()
        compose.onNodeWithTag("quality-menu").performScrollTo().performClick()
        compose.onNodeWithTag("quality-reduced").performClick()
        compose.runOnIdle {
            assertEquals("reduced", appearanceModel.state.value.qualityPreference)
            assertEquals("reduced", EffectQualityManager.preference(compose.activity))
        }
        compose.onNodeWithTag("quality-menu").performClick()
        compose.onNodeWithTag("quality-low").performClick()
        compose.onNodeWithTag("style-liquidglass").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("liquidglass", StyleManager.activeId())
            assertSame(model, compose.activity.homeModel)
            assertSame(appearanceModel, compose.activity.appearanceModel)
            assertEquals(reads, repository.readCount)
            assertEquals("保留搜索草稿", model.state.value.query)
            assertTrue(model.state.value.blockedOnly)
        }
        compose.onNodeWithTag("style-spatial3d").performClick()
        compose.runOnIdle {
            assertEquals("spatial3d", StyleManager.activeId())
            assertSame(model, compose.activity.homeModel)
            assertEquals(reads, repository.readCount)
            assertEquals("保留搜索草稿", model.state.value.query)
        }
        compose.onNodeWithTag("style-fluidgradient").performClick()
        compose.onNodeWithTag("back-home").assertExists()
        compose.runOnIdle {
            assertSame(model, compose.activity.homeModel)
            assertEquals(reads, repository.readCount)
            assertEquals("fluidgradient", StyleManager.activeId())
            assertEquals("保留搜索草稿", model.state.value.query)
            assertTrue(model.state.value.blockedOnly)
        }
        compose.runOnIdle {
            val key = "qa_home_failed_style"
            if (com.afs.guard.style.StyleRegistry.find(key) == null) {
                com.afs.guard.style.StyleRegistry.register(object : com.afs.guard.style.RendererFactory {
                    override fun id() = key
                    override fun displayName(c: android.content.Context) = key
                    override fun create() = com.afs.guard.style.PaperStyle()
                })
                com.afs.guard.style.StyleRegistry.registerCapability(key, com.afs.style.api.ComponentContracts::class.java) {
                    throw IllegalStateException("expected unavailable home renderer")
                }
            }
            StyleManager.select(compose.activity, key)
        }
        compose.runOnIdle {
            assertEquals("paper", compose.activity.componentRenderer?.id)
            assertEquals("paper", StyleManager.activeId())
            assertSame(model, compose.activity.homeModel)
            assertEquals(reads, repository.readCount)
            StyleManager.select(compose.activity, "fluidgradient")
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("back-home").assertExists()
        compose.runOnIdle { assertSame(model, compose.activity.homeModel) }
        compose.runOnIdle { assertSame(appearanceModel, compose.activity.appearanceModel) }
        compose.onNodeWithTag("style-paper").performClick()
        compose.onNodeWithTag("back-home").performClick()
        compose.onNodeWithTag("dashboard-open-apps").performClick()
        compose.onNodeWithTag("app-rules-search").assertTextContains("保留搜索草稿")
        compose.onNodeWithTag("app-rules-blocked").assertIsSelected()
        compose.runOnIdle {
            assertEquals(reads, repository.readCount)
            assertEquals("paper", StyleManager.activeId())
        }
    }
}
