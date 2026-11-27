package com.afs.guard

import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.*
import com.afs.style.liquidglass.LiquidGlassComponents
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class GlassLifecycleTest {
    @Test fun glassIsFourthStyleAndReleasesScenePanelShadersAndMotion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bridge = StylePreferencesBridge.get(context)
        runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
        val prefs = context.getSharedPreferences(Ui.SP, 0)
        val previousStyle = StyleManager.selected(context); val previousQuality = EffectQualityManager.preference(context)
        val reduced = prefs.getBoolean("reduce_motion", false)
        val rules = RuleStore.exportRules(context)
        fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)
        fun eventually(label: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < end) {
                var success = false; main { success = condition() }
                if (success) return
                SystemClock.sleep(80)
            }
            fail(label)
        }
        try {
            // Synthetic QA registrations are allowed by the same open registry.
            assertEquals(setOf("paper", "liquidglass", "spatial3d", "fluidgradient"),
                StyleRegistry.all().map { it.id() }.filterNot { it.startsWith("qa_") }.toSet())
            main { prefs.edit().putBoolean("reduce_motion", false).commit(); StyleManager.select(context, "liquidglass"); EffectQualityManager.setPreference(context, "high") }
            ActivityScenario.launch(SharedHomeActivity::class.java).use { scenario ->
                lateinit var activity: SharedHomeActivity
                scenario.onActivity { activity = it }
                eventually("glass renderer did not attach") { activity.componentRenderer is LiquidGlassComponents && !activity.homeModel.state.value.loading }
                val glass = activity.componentRenderer as LiquidGlassComponents
                val model = activity.homeModel; val reads = activity.repository.readCount
                val expected = if (model.state.value.visibleApps.isNotEmpty()) 2 else 1
                if (Build.VERSION.SDK_INT >= 33) eventually("scene/panel shader failed") { glass.activeShaders >= expected && glass.activeShaders <= 9 }
                scenario.moveToState(Lifecycle.State.CREATED)
                main { assertEquals(0, glass.activeShaders); assertEquals(0, glass.resources.activeCount) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                if (Build.VERSION.SDK_INT >= 33) eventually("glass shaders did not resume") { glass.activeShaders >= expected }
                main { EffectQualityManager.setPreference(context, "medium") }
                if (Build.VERSION.SDK_INT >= 33) eventually("medium glass did not reattach") { glass.activeShaders >= expected }
                main { EffectQualityManager.setPreference(context, "low") }
                eventually("LOW retained GPU or continuous motion") { glass.activeShaders == 0 && glass.resources.activeCount <= 1 }
                val lowTime = glass.animationTime
                SystemClock.sleep(250)
                main { assertEquals(lowTime, glass.animationTime, .001f) }
                main { EffectQualityManager.setPreference(context, "high") }
                if (Build.VERSION.SDK_INT >= 33) eventually("HIGH did not restore shaders") { glass.activeShaders >= expected }
                main { prefs.edit().putBoolean("reduce_motion", true).commit() }
                eventually("reduced motion retained glass effects") { glass.activeShaders == 0 && glass.resources.activeCount <= 1 }
                main { StyleManager.select(context, "paper") }
                eventually("minimal style did not return") { activity.componentRenderer?.id == "paper" }
                main { assertSame(model, activity.homeModel); assertEquals(reads, activity.repository.readCount); assertEquals(0, glass.activeShaders) }
            }
            assertEquals(rules, RuleStore.exportRules(context))
        } finally {
            main { StyleManager.select(context, previousStyle); EffectQualityManager.setPreference(context, previousQuality); prefs.edit().putBoolean("reduce_motion", reduced).commit() }
            runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
        }
    }
}
